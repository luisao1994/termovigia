package com.termovigia.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

/** Uma leitura completa do aparelho num instante. */
data class Sample(
    val ts: Long,
    val batTemp: Float,      // °C da bateria (sensor mais confiável em todo Android)
    val level: Int,          // % de bateria
    val charging: Boolean,
    val plug: Int,           // 0 = fora da tomada
    val currentMa: Int,      // corrente em mA (valor absoluto), -1 se desconhecido
    val voltageMv: Int,
    val thermal: Int,        // estado térmico do sistema (0 a 6), -1 se desconhecido
    val headroom: Float,     // margem térmica do sistema (1.0 = vai começar a limitar), -1 se desconhecido
    val cpuPct: Int,         // frequência média dos núcleos em % do máximo, -1 se desconhecido
    val cpuTemp: Float,      // °C do processador se o sistema deixar ler, -1 se não
    val screenOn: Boolean,
    val brightness: Int,     // % de brilho, -1 se desconhecido
    val app: String,         // pacote do app na tela ("" com tela desligada)
    val net: String,         // "wifi", "mobile", "none"
    val signal: Int          // 0 a 4 (dados móveis), -1 se desconhecido
) {
    val watts: Float get() = if (currentMa < 0 || voltageMv <= 0) -1f else currentMa * voltageMv / 1_000_000f
}

data class Ev(val id: Long, val ts: Long, val kind: String, val temp: Float, val app: String, val msg: String)

data class DaySummary(val day: String, val maxT: Float, val avgT: Float, val pctHot: Int, val n: Int)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("termovigia", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()
    fun boot(c: Context) = sp(c).getBoolean("boot", true)
    fun setBoot(c: Context, v: Boolean) = sp(c).edit().putBoolean("boot", v).apply()
    fun interval(c: Context) = sp(c).getInt("interval", 15)
    fun setInterval(c: Context, v: Int) = sp(c).edit().putInt("interval", v).apply()
    fun alertTemp(c: Context) = sp(c).getInt("alert", 42)
    fun setAlertTemp(c: Context, v: Int) = sp(c).edit().putInt("alert", v).apply()
    fun alerts(c: Context) = sp(c).getBoolean("alerts", true)
    fun setAlerts(c: Context, v: Boolean) = sp(c).edit().putBoolean("alerts", v).apply()
    fun keepDays(c: Context) = sp(c).getInt("keep", 7)
    fun setKeepDays(c: Context, v: Int) = sp(c).edit().putInt("keep", v).apply()
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "termovigia.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE s(ts INTEGER PRIMARY KEY, bt REAL, lvl INTEGER, chg INTEGER, plug INTEGER, ma INTEGER, mv INTEGER," +
                " th INTEGER, hr REAL, cpu INTEGER, ct REAL, scr INTEGER, bri INTEGER, app TEXT, net TEXT, sig INTEGER)"
        )
        db.execSQL("CREATE TABLE e(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, temp REAL, app TEXT, msg TEXT)")
        db.execSQL("CREATE INDEX e_ts ON e(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun insert(s: Sample) {
        val v = ContentValues()
        v.put("ts", s.ts); v.put("bt", s.batTemp); v.put("lvl", s.level); v.put("chg", if (s.charging) 1 else 0)
        v.put("plug", s.plug); v.put("ma", s.currentMa); v.put("mv", s.voltageMv); v.put("th", s.thermal)
        v.put("hr", s.headroom); v.put("cpu", s.cpuPct); v.put("ct", s.cpuTemp); v.put("scr", if (s.screenOn) 1 else 0)
        v.put("bri", s.brightness); v.put("app", s.app); v.put("net", s.net); v.put("sig", s.signal)
        writableDatabase.insertWithOnConflict("s", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun samples(since: Long, until: Long = Long.MAX_VALUE): List<Sample> {
        val out = ArrayList<Sample>()
        readableDatabase.rawQuery(
            "SELECT ts,bt,lvl,chg,plug,ma,mv,th,hr,cpu,ct,scr,bri,app,net,sig FROM s WHERE ts>=? AND ts<=? ORDER BY ts",
            arrayOf(since.toString(), until.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Sample(
                        c.getLong(0), c.getFloat(1), c.getInt(2), c.getInt(3) == 1, c.getInt(4), c.getInt(5), c.getInt(6),
                        c.getInt(7), c.getFloat(8), c.getInt(9), c.getFloat(10), c.getInt(11) == 1, c.getInt(12),
                        c.getString(13) ?: "", c.getString(14) ?: "", c.getInt(15)
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun addEvent(ts: Long, kind: String, temp: Float, app: String, msg: String) {
        val v = ContentValues()
        v.put("ts", ts); v.put("kind", kind); v.put("temp", temp); v.put("app", app); v.put("msg", msg)
        writableDatabase.insert("e", null, v)
    }

    @Synchronized
    fun events(limit: Int = 300): List<Ev> {
        val out = ArrayList<Ev>()
        readableDatabase.rawQuery("SELECT id,ts,kind,temp,app,msg FROM e ORDER BY ts DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out.add(Ev(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getFloat(3), c.getString(4) ?: "", c.getString(5) ?: ""))
        }
        return out
    }

    @Synchronized
    fun days(hot: Int): List<DaySummary> {
        val out = ArrayList<DaySummary>()
        readableDatabase.rawQuery(
            "SELECT date(ts/1000,'unixepoch','localtime') d, MAX(bt), AVG(bt), SUM(CASE WHEN bt>=? THEN 1 ELSE 0 END), COUNT(*) " +
                "FROM s GROUP BY d ORDER BY d DESC LIMIT 14",
            arrayOf(hot.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(4)
                out.add(DaySummary(c.getString(0) ?: "", c.getFloat(1), c.getFloat(2), if (n > 0) c.getInt(3) * 100 / n else 0, n))
            }
        }
        return out
    }

    @Synchronized
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM s", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    @Synchronized
    fun firstTs(): Long = readableDatabase.rawQuery("SELECT MIN(ts) FROM s", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    @Synchronized
    fun prune(days: Int) {
        val cut = System.currentTimeMillis() - days * 86_400_000L
        writableDatabase.delete("s", "ts<?", arrayOf(cut.toString()))
        writableDatabase.delete("e", "ts<?", arrayOf(cut.toString()))
    }

    @Synchronized
    fun clearEvents() { writableDatabase.delete("e", null, null) }

    @Synchronized
    fun clearAll() { writableDatabase.delete("s", null, null); writableDatabase.delete("e", null, null) }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c.applicationContext).also { inst = it } }
    }
}

/** Formatação em português. */
object F {
    private val br = Locale("pt", "BR")
    fun t(v: Float) = if (v < 0) "—" else String.format(br, "%.1f °C", v)
    fun d1(v: Float) = String.format(br, "%.1f", v)
    fun w(v: Float) = if (v < 0) "—" else String.format(br, "%.1f W", v)
    fun time(ts: Long): String = java.text.SimpleDateFormat("HH:mm", br).format(java.util.Date(ts))
    fun dateTime(ts: Long): String = java.text.SimpleDateFormat("dd/MM HH:mm", br).format(java.util.Date(ts))
    fun mins(m: Float): String = when {
        m < 1f -> "menos de 1 min"
        m < 60f -> "${m.toInt()} min"
        else -> { val h = (m / 60).toInt(); val r = (m % 60).toInt(); if (r == 0) "${h}h" else "${h}h${String.format(br, "%02d", r)}" }
    }
    fun bytes(b: Long): String = when {
        b >= 1_073_741_824L -> String.format(br, "%.2f GB", b / 1_073_741_824.0)
        b >= 1_048_576L -> String.format(br, "%.0f MB", b / 1_048_576.0)
        else -> String.format(br, "%.0f KB", b / 1024.0)
    }

    fun status(t: Float): String = when {
        t < 0 -> "—"
        t < 38f -> "Normal"
        t < 41f -> "Morno"
        t < 44f -> "Quente"
        t < 47f -> "Muito quente"
        else -> "Perigoso"
    }

    fun thermal(s: Int): String = when (s) {
        0 -> "Normal"
        1 -> "Leve"
        2 -> "Moderado"
        3 -> "Grave (limitando)"
        4 -> "Crítico"
        5 -> "Emergência"
        6 -> "Desligando"
        else -> "—"
    }
}
