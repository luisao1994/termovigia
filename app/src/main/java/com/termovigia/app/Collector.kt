package com.termovigia.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telephony.TelephonyManager
import java.io.File
import kotlin.math.abs

data class Core(val id: Int, val curKhz: Int, val maxKhz: Int)
data class Zone(val type: String, val temp: Float)

/** Dados extras do carregamento: contador de carga da bateria e entrada USB (se o sistema deixar ler). */
data class ChargeRaw(val counterUah: Long, val usbV: Float, val usbA: Float)

/** Lê tudo o que o Android deixa um app comum ler sobre aquecimento e consumo. */
class Collector(private val c: Context) {
    private val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private var lastQuery = 0L
    private var fg = ""
    private val maxFreq = HashMap<Int, Int>()

    fun sample(): Sample {
        val now = System.currentTimeMillis()
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) ?: -10) / 10f
        val lvlRaw = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (lvlRaw >= 0 && scale > 0) lvlRaw * 100 / scale else -1
        val plug = bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = plug != 0 || st == BatteryManager.BATTERY_STATUS_CHARGING
        var mv = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (mv > 100_000) mv /= 1000

        val screen = pm.isInteractive
        val cores = cores()
        val sumMax = cores.sumOf { it.maxKhz.toLong() }
        val cpuPct = if (sumMax > 0) (cores.sumOf { it.curKhz.toLong() } * 100 / sumMax).toInt() else -1

        return Sample(
            ts = now,
            batTemp = temp,
            level = level,
            charging = charging,
            plug = plug,
            currentMa = currentMa(),
            voltageMv = mv,
            thermal = if (Build.VERSION.SDK_INT >= 29) try { pm.currentThermalStatus } catch (e: Throwable) { -1 } else -1,
            headroom = if (Build.VERSION.SDK_INT >= 30) try { pm.getThermalHeadroom(0).let { if (it.isNaN()) -1f else it } } catch (e: Throwable) { -1f } else -1f,
            cpuPct = cpuPct,
            cpuTemp = cpuTemp(),
            screenOn = screen,
            brightness = brightness(),
            app = if (screen) foreground() else { foreground(); "" },
            net = net(),
            signal = signal()
        )
    }

    private fun currentMa(): Int {
        var raw = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (e: Throwable) { Int.MIN_VALUE }
        if (raw == Int.MIN_VALUE || raw == 0) {
            raw = readLong("/sys/class/power_supply/battery/current_now")?.toInt() ?: return -1
        }
        var ma = abs(raw)
        if (ma > 30_000) ma /= 1000 // veio em microampère
        return ma
    }

    fun chargeRaw(): ChargeRaw {
        val counter = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong() } catch (e: Throwable) { -1L }
        val base = "/sys/class/power_supply/usb/"
        var v = (readLong(base + "voltage_now") ?: -1L).toFloat()
        v = when { v > 100_000f -> v / 1_000_000f; v > 1000f -> v / 1000f; else -> v }
        var a = (readLong(base + "input_current_now") ?: readLong(base + "current_now") ?: -1L).toFloat()
        a = kotlin.math.abs(a)
        a = if (a > 30_000f) a / 1_000_000f else a / 1000f
        val ok = v in 3f..25f && a in 0.05f..15f
        return ChargeRaw(if (counter == Long.MIN_VALUE || counter == Int.MIN_VALUE.toLong()) -1L else counter, if (ok) v else -1f, if (ok) a else -1f)
    }

    fun cores(): List<Core> {
        val out = ArrayList<Core>()
        for (i in 0 until 16) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq/"
            if (!File("/sys/devices/system/cpu/cpu$i").exists()) break
            val cur = readLong(base + "scaling_cur_freq")?.toInt() ?: continue
            val max = maxFreq[i] ?: (readLong(base + "cpuinfo_max_freq") ?: readLong(base + "scaling_max_freq"))?.toInt()?.also { maxFreq[i] = it } ?: continue
            if (max > 0) out.add(Core(i, cur, max))
        }
        return out
    }

    fun zones(): List<Zone> {
        val dirs = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return emptyList()
        val out = ArrayList<Zone>()
        for (d in dirs) {
            val type = try { File(d, "type").readText().trim() } catch (e: Throwable) { continue }
            val raw = readLong(File(d, "temp").path) ?: continue
            val t = if (abs(raw) > 1000) raw / 1000f else raw.toFloat()
            if (t > -20f && t < 150f && t != 0f) out.add(Zone(type, t))
        }
        return out.sortedByDescending { it.temp }
    }

    private val cpuRx = Regex("cpu|soc|tsens|apc|cluster|cpuss|mtktscpu", RegexOption.IGNORE_CASE)
    private fun cpuTemp(): Float {
        val z = zones().filter { cpuRx.containsMatchIn(it.type) }
        return z.maxOfOrNull { it.temp } ?: -1f
    }

    private fun brightness(): Int {
        val cr = c.contentResolver
        try {
            val f = Settings.System.getFloat(cr, "screen_brightness_float")
            if (f in 0f..1f) return (f * 100).toInt()
        } catch (e: Throwable) {}
        return try {
            val v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
            val max = if (v > 255) (if (v > 2047) 4095 else 2047) else 255
            (v * 100 / max).coerceIn(0, 100)
        } catch (e: Throwable) { -1 }
    }

    private fun net(): String = try {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    } catch (e: Throwable) { "none" }

    private fun signal(): Int = try {
        if (Build.VERSION.SDK_INT >= 28) tm?.signalStrength?.level ?: -1 else -1
    } catch (e: Throwable) { -1 }

    /** App que está na tela agora (precisa de "Acesso ao uso"). */
    private fun foreground(): String {
        val u = um ?: return ""
        if (!hasUsageAccess(c)) return ""
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 6 * 3_600_000L else lastQuery - 2_000
        try {
            val evs = u.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (evs.hasNextEvent()) {
                evs.getNextEvent(e)
                if (e.eventType == 1) fg = e.packageName ?: fg // 1 = app foi para a tela
            }
            lastQuery = now
        } catch (e: Throwable) {}
        return fg
    }

    private fun readLong(path: String): Long? = try { File(path).readText().trim().toLong() } catch (e: Throwable) { null }

    companion object {
        fun hasUsageAccess(c: Context): Boolean = try {
            val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29)
                ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            else @Suppress("DEPRECATION") ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) { false }
    }
}

/** Nomes amigáveis dos apps. */
object Names {
    private val cache = HashMap<String, String>()
    private var launchers: Set<String>? = null

    fun label(c: Context, pkg: String): String {
        if (pkg.isEmpty()) return "Tela desligada"
        cache[pkg]?.let { return it }
        val pm = c.packageManager
        if (launchers == null) launchers = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
        } catch (e: Throwable) { emptySet() }
        val name = when {
            pkg == c.packageName -> "TermoVigia (este app)"
            launchers?.contains(pkg) == true -> "Tela inicial"
            else -> try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: PackageManager.NameNotFoundException) { pkg } catch (e: Throwable) { pkg }
        }
        cache[pkg] = name
        return name
    }
}
