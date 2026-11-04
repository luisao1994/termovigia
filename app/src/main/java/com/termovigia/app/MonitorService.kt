package com.termovigia.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import kotlin.math.max

/** Roda em segundo plano o tempo todo, lendo os sensores e salvando o histórico. */
class MonitorService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var last: Sample? = null
        const val CH_RUN = "run"
        const val CH_ALERT = "alert"
        private const val NID = 1

        fun start(c: Context) {
            Prefs.setEnabled(c, true)
            val i = Intent(c, MonitorService::class.java)
            try { c.startForegroundService(i) } catch (e: Throwable) {}
        }

        fun stop(c: Context) {
            Prefs.setEnabled(c, false)
            c.stopService(Intent(c, MonitorService::class.java))
        }

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_RUN, "Monitoramento ativo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mostra a temperatura atual enquanto o TermoVigia monitora"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alertas de aquecimento", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisa quando o celular esquenta fora do normal"
            })
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private lateinit var col: Collector
    private lateinit var db: Db
    private val lastAlert = HashMap<String, Long>()
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (e: Throwable) {}
            h.postDelayed(this, delayMs())
        }
    }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            h.removeCallbacks(tick)
            h.postDelayed(tick, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        channels(this)
        val n = runNotif("Iniciando monitoramento…", "")
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NID, n)
        } catch (e: Throwable) {
            // o sistema não deixou iniciar agora (ex.: em segundo plano); religa quando o app for aberto
            stopSelf()
        }
        running = true
        db = Db.get(this)
        col = Collector(this)
        thread = HandlerThread("termovigia").also { it.start() }
        h = Handler(thread.looper)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(screenRx, f)
        h.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(screenRx) } catch (e: Throwable) {}
        h.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Tela desligada: lê com menos frequência para não gastar bateria. */
    private fun delayMs(): Long {
        val base = Prefs.interval(this)
        val screen = last?.screenOn ?: true
        return (if (screen) base else max(base, 60)) * 1000L
    }

    private fun step() {
        val s = col.sample()
        last = s
        db.insert(s)
        val recent = db.samples(s.ts - 6 * 60_000L)
        checkAlerts(s, recent)
        val app = if (s.screenOn && s.app.isNotEmpty()) Names.label(this, s.app) else if (!s.screenOn) "tela desligada" else ""
        val parts = ArrayList<String>()
        if (s.watts > 0) parts.add(F.w(s.watts))
        if (s.charging) parts.add("carregando")
        if (app.isNotEmpty()) parts.add(app)
        getSystemService(NotificationManager::class.java).notify(NID, runNotif("${F.t(s.batTemp)} · ${F.status(s.batTemp)}", parts.joinToString(" · ")))
        if (++ticks % 200 == 1) db.prune(Prefs.keepDays(this))
    }

    private fun checkAlerts(s: Sample, recent: List<Sample>) {
        val alert = Prefs.alertTemp(this)
        val ref = recent.firstOrNull { s.ts - it.ts in 180_000L..390_000L }
        val rise = if (ref != null) s.batTemp - ref.batTemp else 0f
        val causes = Analyzer.liveCauses(this, s, recent)
        val who = Analyzer.suspect(this, recent)
        val app = who ?: ""
        val why = if (causes.isEmpty()) "" else "\nPossíveis causas: " + causes.joinToString("; ")

        when {
            s.batTemp >= 45f -> fire("crit", "🔥 Celular muito quente: ${F.t(s.batTemp)}", "Pare de usar um pouco, tire a capinha e desconecte o carregador.$why", s, app)
            s.batTemp >= alert -> fire("hot", "Celular quente: ${F.t(s.batTemp)}", "Passou do seu limite de $alert °C.$why", s, app)
        }
        if (rise >= 3f && s.batTemp >= 37f)
            fire("rise", "Esquentando rápido (+${F.d1(rise)} °C)", "A temperatura subiu ${F.d1(rise)} °C em poucos minutos.$why", s, app)
        if (!s.screenOn && !s.charging && s.batTemp >= 37f && rise >= 1.5f)
            fire("bg", "Esquentando com a tela desligada", "Está em ${F.t(s.batTemp)} e subindo sem você usar. Algum app está trabalhando em segundo plano. Abra o TermoVigia > Causas > Uso de internet.", s, "")
        if (s.thermal >= 3)
            fire("throttle", "Sistema limitando o desempenho", "O Android entrou em estado térmico \"${F.thermal(s.thermal)}\" e está reduzindo a velocidade para esfriar.$why", s, app)
    }

    private fun fire(kind: String, title: String, msg: String, s: Sample, app: String) {
        val now = s.ts
        if (now - (lastAlert[kind] ?: 0L) < 15 * 60_000L) return
        lastAlert[kind] = now
        db.addEvent(now, kind, s.batTemp, app, title + "\n" + msg)
        if (!Prefs.alerts(this)) return
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(msg.replace("\n", " "))
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setContentIntent(openApp(2))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(100 + kind.hashCode() % 50, n)
    }

    private fun openApp(tab: Int): PendingIntent = PendingIntent.getActivity(
        this, tab, Intent(this, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun runNotif(title: String, text: String): Notification =
        Notification.Builder(this, CH_RUN)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(0))
            .build()
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs.enabled(c) && (Prefs.boot(c) || i.action == Intent.ACTION_MY_PACKAGE_REPLACED)) MonitorService.start(c)
    }
}
