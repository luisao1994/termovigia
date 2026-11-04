package com.termovigia.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var headerSub: TextView
    private val nav = ArrayList<TextView>()
    private var tab = 0

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("ui-worker").also { it.start() }
    private val bg = Handler(worker.looper)
    private val col by lazy { Collector(this) }
    private val db by lazy { Db.get(this) }

    private var chartHours = 3
    private var periodHours = 24
    private var refreshN = 0
    private var resumed = false

    // peças da aba "Agora" atualizadas ao vivo
    private var liveBox: LinearLayout? = null
    private var chart: ChartView? = null
    private var hwBox: LinearLayout? = null

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.CARD
        MonitorService.channels(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
        }
        header.addView(Ui.text(this, "TermoVigia", 22f, Ui.TEXT, true))
        headerSub = Ui.text(this, "", 13f, Ui.MUTED)
        header.addView(headerSub)
        root.addView(header)

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(24)) }
        scroll.addView(body)
        root.addView(scroll)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.CARD)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        listOf("🌡️\nAgora", "🔎\nCausas", "🔔\nAlertas", "⚙️\nAjustes").forEachIndexed { i, s ->
            val b = Ui.text(this, s, 12f, Ui.MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { show(i) }
            }
            nav.add(b); bar.addView(b)
        }
        root.addView(bar)
        setContentView(root)

        // Android 15+ desenha o app por baixo da barra de status e dos botões de navegação:
        // empurra o cabeçalho para baixo e as abas para cima, para nada ficar escondido.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int; val bottom: Int; val left: Int; val right: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val sb = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                top = sb.top; bottom = sb.bottom; left = sb.left; right = sb.right
            } else {
                @Suppress("DEPRECATION")
                run { top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom; left = insets.systemWindowInsetLeft; right = insets.systemWindowInsetRight }
            }
            header.setPadding(dp(18) + left, dp(12) + top, dp(18) + right, dp(8))
            scroll.setPadding(left, 0, right, 0)
            bar.setPadding(dp(6) + left, dp(6), dp(6) + right, dp(6) + bottom)
            insets
        }
        root.requestApplyInsets()

        tab = intent?.getIntExtra("tab", 0) ?: 0
        if (savedInstanceState == null && !Prefs.enabled(this) && db.count() == 0) firstRun()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("tab")) show(intent.getIntExtra("tab", 0))
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // se o usuário ligou o monitoramento e o sistema matou o serviço, religa
        if (Prefs.enabled(this) && !MonitorService.running) MonitorService.start(this)
        show(tab)
        main.removeCallbacks(refresher)
        main.postDelayed(refresher, 3000)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(refresher)
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private val refresher = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (tab == 0) refreshNow(++refreshN % 5 == 0)
            updateHeader()
            main.postDelayed(this, 3000)
        }
    }

    private fun updateHeader() {
        val on = Prefs.enabled(this) && MonitorService.running
        headerSub.text = if (on) "● Monitorando em segundo plano" else "○ Monitoramento desligado"
        headerSub.setTextColor(if (on) Ui.GREEN else Ui.MUTED)
    }

    private fun show(t: Int) {
        tab = t
        nav.forEachIndexed { i, v -> v.setTextColor(if (i == t) Ui.ACCENT else Ui.MUTED) }
        body.removeAllViews()
        liveBox = null; chart = null; hwBox = null
        updateHeader()
        when (t) {
            0 -> buildNow()
            1 -> buildCauses()
            2 -> buildAlerts()
            else -> buildSettings()
        }
        scroll.scrollTo(0, 0)
    }

    // ------------------------------------------------------------------ AGORA

    private fun buildNow() {
        val running = Prefs.enabled(this) && MonitorService.running
        if (!running) {
            body.addView(Ui.card(this, Ui.CARD2).apply {
                addView(Ui.title(this@MainActivity, "Monitoramento desligado"))
                addView(Ui.text(context, "Ative para o TermoVigia ficar medindo tudo em segundo plano (inclusive depois de reiniciar o celular) e descobrir o que está esquentando.", 13.5f, Ui.MUTED))
                addView(Ui.button(context, "Ativar monitoramento") { activate() })
            })
        }
        setupCard()?.let { body.addView(it) }

        liveBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(liveBox)
        liveBox!!.addView(Ui.card(this).apply { addView(Ui.text(context, "Lendo sensores…", 14f, Ui.MUTED)) })

        val cc = Ui.card(this)
        cc.addView(Ui.title(this, "Temperatura ao longo do tempo"))
        cc.addView(Ui.chips(this, listOf("1h" to 1, "3h" to 3, "12h" to 12, "24h" to 24, "3 dias" to 72), chartHours) { chartHours = it; show(0) })
        chart = ChartView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)) }
        cc.addView(chart)
        val legend = Ui.text(this, "Linha laranja: seu limite de alerta (${Prefs.alertTemp(this)} °C) • Faixa azul: carregando • Faixa cinza: tela desligada", 11.5f, Ui.MUTED)
        legend.setPadding(0, dp(6), 0, 0)
        cc.addView(legend)
        body.addView(cc)

        hwBox = Ui.card(this)
        body.addView(hwBox)

        refreshNow(true)
    }

    private fun refreshNow(withChart: Boolean) {
        val hours = chartHours
        bg.post {
            val s = col.sample()
            val zones = col.zones()
            val cores = col.cores()
            val since = System.currentTimeMillis()
            val recent = db.samples(since - 10 * 60_000L)
            val svc = MonitorService.last
            // o app na tela agora é o próprio TermoVigia; usa o que o serviço viu por último antes dele
            val causes = Analyzer.liveCauses(this, s, recent)
            val hist = if (withChart) db.samples(since - hours * 3_600_000L) else null
            val first = db.firstTs()
            val n = db.count()
            val lastApp = svcLastApp(svc)
            main.post { if (tab == 0 && !isFinishing) renderLive(s, zones, cores, causes, hist, lastApp, first, n) }
        }
    }

    private fun renderLive(s: Sample, zones: List<Zone>, cores: List<Core>, causes: List<String>, hist: List<Sample>?, lastApp: String?, first: Long, n: Int) {
        val box = liveBox ?: return
        box.removeAllViews()

        // cartão grande
        val big = Ui.card(this)
        big.addView(Ui.text(this, "Temperatura da bateria", 13f, Ui.MUTED))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        row.addView(Ui.text(this, F.t(s.batTemp), 46f, Ui.tempColor(s.batTemp), true))
        row.addView(Ui.text(this, "  " + F.status(s.batTemp), 18f, Ui.tempColor(s.batTemp), true).apply { setPadding(0, 0, 0, dp(10)) })
        big.addView(row)
        val sub = ArrayList<String>()
        if (s.cpuTemp > 0) sub.add("Processador: ${F.t(s.cpuTemp)}")
        sub.add(if (s.charging) "Carregando • ${s.level}%" else "Bateria ${s.level}%")
        big.addView(Ui.text(this, sub.joinToString("   "), 13.5f, Ui.MUTED))
        big.addView(Ui.bar(this, (s.batTemp - 25f) / 25f, Ui.tempColor(s.batTemp)))
        box.addView(big)

        // grade de números
        val grid = Ui.card(this)
        grid.addView(Ui.title(this, "Agora"))
        grid.addView(Ui.kv(this, "Estado térmico do sistema", F.thermal(s.thermal), if (s.thermal >= 3) Ui.RED else if (s.thermal >= 1) Ui.ORANGE else Ui.GREEN))
        if (s.headroom >= 0) {
            val pct = (s.headroom * 100).toInt()
            grid.addView(Ui.kv(this, "Uso da margem térmica", "$pct%", if (pct >= 100) Ui.RED else if (pct >= 80) Ui.ORANGE else Ui.TEXT))
        }
        val w = s.watts
        grid.addView(Ui.kv(this, if (s.charging) "Potência entrando/saindo" else "Consumo do aparelho", if (s.currentMa >= 0) "${F.w(w)}  (${s.currentMa} mA)" else "—", if (!s.charging && w >= 5) Ui.ORANGE else Ui.TEXT))
        grid.addView(Ui.kv(this, "Processador (velocidade)", if (s.cpuPct >= 0) "${s.cpuPct}% do máximo" else "bloqueado pelo sistema", if (s.cpuPct >= 70) Ui.ORANGE else Ui.TEXT))
        grid.addView(Ui.kv(this, "Rede", when (s.net) { "wifi" -> "Wi-Fi"; "mobile" -> "Dados móveis" + (if (s.signal >= 0) " • sinal ${s.signal}/4" else ""); "none" -> "Sem conexão"; else -> "Outra" }, if (s.net == "mobile" && s.signal in 0..1) Ui.ORANGE else Ui.TEXT))
        if (s.brightness >= 0) grid.addView(Ui.kv(this, "Brilho da tela", "${s.brightness}%", if (s.brightness >= 85) Ui.ORANGE else Ui.TEXT))
        if (lastApp != null) grid.addView(Ui.kv(this, "Último app usado", lastApp))
        if (n > 0) grid.addView(Ui.kv(this, "Leituras salvas", "$n desde ${F.dateTime(first)}"))
        box.addView(grid)

        // causas
        val cz = Ui.card(this)
        cz.addView(Ui.title(this, "O que pode estar esquentando agora"))
        if (causes.isEmpty()) cz.addView(Ui.text(this, "✅ Nada fora do normal neste momento.", 13.5f, Ui.GREEN))
        else causes.forEach { cz.addView(Ui.text(this, "• $it", 13.5f, Ui.TEXT).apply { setPadding(0, dp(3), 0, dp(3)) }) }
        if (!Collector.hasUsageAccess(this)) cz.addView(Ui.text(this, "Dê o \"Acesso ao uso\" (Ajustes) para o app saber qual aplicativo estava aberto quando esquentou.", 12.5f, Ui.ORANGE).apply { setPadding(0, dp(8), 0, 0) })
        box.addView(cz)

        if (hist != null) chart?.set(hist, chartHours, Prefs.alertTemp(this), Prefs.interval(this))

        // hardware
        hwBox?.let { hb ->
            hb.removeAllViews()
            hb.addView(Ui.title(this, "Sensores do aparelho"))
            if (cores.isNotEmpty()) {
                hb.addView(Ui.text(this, "Núcleos do processador", 13f, Ui.MUTED, true))
                cores.forEach { c ->
                    val f = c.curKhz.toFloat() / c.maxKhz
                    hb.addView(Ui.kv(this, "Núcleo ${c.id}", "${c.curKhz / 1000} / ${c.maxKhz / 1000} MHz", if (f >= 0.85f) Ui.ORANGE else Ui.TEXT))
                }
            } else hb.addView(Ui.text(this, "O sistema não deixa ler a velocidade dos núcleos.", 13f, Ui.MUTED))
            hb.addView(Ui.text(this, "Sensores de temperatura internos", 13f, Ui.MUTED, true).apply { setPadding(0, dp(12), 0, 0) })
            if (zones.isNotEmpty()) zones.take(15).forEach { z -> hb.addView(Ui.kv(this, z.type, F.t(z.temp), Ui.tempColor(z.temp))) }
            else hb.addView(Ui.text(this, "O HyperOS bloqueia a leitura direta desses sensores para apps comuns. O TermoVigia usa a temperatura da bateria e o estado térmico oficial do Android, que são confiáveis para descobrir o aquecimento.", 13f, Ui.MUTED))
        }
    }

    private fun svcLastApp(svc: Sample?): String? {
        val recent = db.samples(System.currentTimeMillis() - 30 * 60_000L)
        return Analyzer.suspect(this, recent) ?: svc?.app?.takeIf { it.isNotEmpty() && it != packageName }?.let { Names.label(this, it) }
    }

    private fun setupCard(): View? {
        val missing = ArrayList<Pair<String, () -> Unit>>()
        if (!Collector.hasUsageAccess(this)) missing.add("Permitir \"Acesso ao uso\" (descobrir qual app esquenta)" to { openUsageAccess() })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            missing.add("Permitir notificações (alertas de calor)" to { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1) })
        if (!ignoringBattery()) missing.add("Liberar da economia de bateria (não ser fechado)" to { openBatteryExemption() })
        if (missing.isEmpty()) return null
        return Ui.card(this, Ui.CARD2).apply {
            addView(Ui.title(this@MainActivity, "Falta configurar ${missing.size} ${if (missing.size == 1) "coisa" else "coisas"}"))
            missing.forEach { (label, action) -> addView(Ui.button(context, label, false) { action() }) }
        }
    }

    // ------------------------------------------------------------------ CAUSAS

    private fun buildCauses() {
        body.addView(Ui.chips(this, listOf("3h" to 3, "12h" to 12, "24h" to 24, "3 dias" to 72, "7 dias" to 168), periodHours) { periodHours = it; show(1) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(box)
        box.addView(Ui.card(this).apply { addView(Ui.text(context, "Analisando o histórico…", 14f, Ui.MUTED)) })
        val hours = periodHours
        bg.post {
            val r = Analyzer.analyze(this, hours)
            main.post { if (tab == 1 && periodHours == hours) renderCauses(box, r) }
        }
    }

    private fun renderCauses(box: LinearLayout, r: Report) {
        box.removeAllViews()
        val sum = Ui.card(this)
        sum.addView(Ui.title(this, "Resumo"))
        sum.addView(Ui.kv(this, "Tempo monitorado", F.mins(r.coveredMin)))
        sum.addView(Ui.kv(this, "Temperatura máxima", F.t(r.maxT) + if (r.maxTs > 0) "  (${F.dateTime(r.maxTs)})" else "", Ui.tempColor(r.maxT)))
        sum.addView(Ui.kv(this, "Temperatura média", F.t(r.avgT), Ui.tempColor(r.avgT)))
        sum.addView(Ui.kv(this, "Tempo acima de ${Prefs.alertTemp(this)} °C", F.mins(r.minAboveAlert), if (r.minAboveAlert >= 10) Ui.ORANGE else Ui.TEXT))
        box.addView(sum)

        box.addView(Ui.text(this, "Diagnóstico", 16f, Ui.TEXT, true).apply { setPadding(dp(4), dp(4), 0, dp(8)) })
        r.findings.forEach { box.addView(Ui.finding(this, it)) }

        val ac = Ui.card(this)
        ac.addView(Ui.title(this, "Apps que mais esquentam"))
        if (!Collector.hasUsageAccess(this)) {
            ac.addView(Ui.text(this, "Sem o \"Acesso ao uso\" o Android não informa qual app estava aberto.", 13.5f, Ui.ORANGE))
            ac.addView(Ui.button(this, "Dar acesso ao uso", false) { openUsageAccess() })
        } else if (r.apps.isEmpty()) {
            ac.addView(Ui.text(this, "Ainda não há uso suficiente (mínimo 3 minutos por app, fora do carregador).", 13.5f, Ui.MUTED))
        } else {
            val top = r.apps.maxOf { it.score }.coerceAtLeast(0.1f)
            r.apps.take(12).forEachIndexed { i, a ->
                val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
                item.addView(Ui.kv(this, "${i + 1}. ${a.label}", "média ${F.t(a.avgT)}", Ui.tempColor(a.avgT)))
                val det = ArrayList<String>()
                det.add("${F.mins(a.minutes)} de uso")
                det.add("máx ${F.t(a.maxT)}")
                det.add((if (a.risePer10 >= 0) "+" else "") + "${F.d1(a.risePer10)} °C a cada 10 min")
                if (a.avgW > 0) det.add(F.w(a.avgW))
                if (a.avgCpu >= 0) det.add("CPU ${a.avgCpu}%")
                item.addView(Ui.text(this, det.joinToString(" • "), 12.5f, Ui.MUTED))
                item.addView(Ui.bar(this, a.score / top, Ui.tempColor(a.avgT)))
                ac.addView(item)
            }
            ac.addView(Ui.text(this, "Ordem calculada pela temperatura média, por quanto esquenta por minuto e pelo consumo. O tempo carregando fica de fora para não confundir.", 11.5f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
        }
        box.addView(ac)

        val dc = Ui.card(this)
        dc.addView(Ui.title(this, "Uso de internet no período"))
        dc.addView(Ui.text(this, "App usando muita internet sem você abrir é sinal de trabalho em segundo plano (backup, sincronização, atualização) — e isso esquenta.", 12.5f, Ui.MUTED).apply { setPadding(0, 0, 0, dp(6)) })
        if (r.data.isEmpty()) dc.addView(Ui.text(this, if (Collector.hasUsageAccess(this)) "Nada relevante." else "Precisa do \"Acesso ao uso\".", 13.5f, Ui.MUTED))
        else {
            val mx = r.data.first().bytes.toFloat()
            r.data.forEach { d ->
                dc.addView(Ui.kv(this, d.label, F.bytes(d.bytes)))
                dc.addView(Ui.bar(this, d.bytes / mx, Ui.ACCENT))
            }
        }
        box.addView(dc)

        box.addView(Ui.button(this, "Compartilhar relatório") {
            val txt = Analyzer.reportText(this, r)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Enviar relatório"))
        })
    }

    // ------------------------------------------------------------------ ALERTAS

    private fun buildAlerts() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(box)
        bg.post {
            val evs = db.events()
            val days = db.days(Prefs.alertTemp(this))
            main.post { if (tab == 2) renderAlerts(box, evs, days) }
        }
    }

    private fun renderAlerts(box: LinearLayout, evs: List<Ev>, days: List<DaySummary>) {
        if (days.isNotEmpty()) {
            val dc = Ui.card(this)
            dc.addView(Ui.title(this, "Resumo por dia"))
            days.forEach { d ->
                val p = d.day.split("-")
                val label = if (p.size == 3) "${p[2]}/${p[1]}" else d.day
                dc.addView(Ui.kv(this, label, "máx ${F.t(d.maxT)} • média ${F.t(d.avgT)} • ${d.pctHot}% quente", Ui.tempColor(d.maxT)))
            }
            box.addView(dc)
        }
        box.addView(Ui.text(this, "Alertas registrados", 16f, Ui.TEXT, true).apply { setPadding(dp(4), dp(4), 0, dp(8)) })
        if (evs.isEmpty()) {
            box.addView(Ui.card(this).apply { addView(Ui.text(context, "Nenhum aquecimento fora do normal registrado ainda. 👍", 14f, Ui.MUTED)) })
            return
        }
        evs.forEach { e ->
            val lines = e.msg.split("\n", limit = 2)
            val lvl = when (e.kind) { "crit", "throttle", "bg" -> 2; else -> 1 }
            val extra = (if (e.app.isNotEmpty()) "App mais usado: ${e.app}\n" else "") + (lines.getOrNull(1) ?: "")
            box.addView(Ui.finding(this, Finding(lvl, "${F.dateTime(e.ts)} • ${lines[0]}", extra.trim())))
        }
        box.addView(Ui.button(this, "Limpar alertas", false, Ui.RED) {
            confirm("Apagar todos os alertas registrados?") { db.clearEvents(); show(2) }
        })
    }

    // ------------------------------------------------------------------ AJUSTES

    private fun buildSettings() {
        val c1 = Ui.card(this)
        c1.addView(Ui.title(this, "Monitoramento"))
        c1.addView(switchRow("Monitorar em segundo plano", Prefs.enabled(this) && MonitorService.running) { on -> if (on) activate() else { MonitorService.stop(this); show(3) } })
        c1.addView(switchRow("Ligar sozinho quando o celular reiniciar", Prefs.boot(this)) { Prefs.setBoot(this, it) })
        c1.addView(switchRow("Notificar quando esquentar", Prefs.alerts(this)) { Prefs.setAlerts(this, it) })
        c1.addView(Ui.text(this, "Ler a cada", 13.5f, Ui.MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        c1.addView(Ui.chips(this, listOf("5 s" to 5, "10 s" to 10, "15 s" to 15, "30 s" to 30, "60 s" to 60), Prefs.interval(this)) { Prefs.setInterval(this, it); show(3) })
        c1.addView(Ui.text(this, "Com a tela desligada ele lê no máximo 1 vez por minuto, para quase não gastar bateria.", 12f, Ui.MUTED))
        c1.addView(Ui.text(this, "Avisar a partir de", 13.5f, Ui.MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        c1.addView(Ui.chips(this, (39..45).map { "$it °C" to it }, Prefs.alertTemp(this)) { Prefs.setAlertTemp(this, it); show(3) })
        c1.addView(Ui.text(this, "Guardar histórico por", 13.5f, Ui.MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        c1.addView(Ui.chips(this, listOf("3 dias" to 3, "7 dias" to 7, "14 dias" to 14, "30 dias" to 30), Prefs.keepDays(this)) { Prefs.setKeepDays(this, it); show(3) })
        body.addView(c1)

        val c2 = Ui.card(this)
        c2.addView(Ui.title(this, "Permissões"))
        c2.addView(permRow("Acesso ao uso", "Saber qual app estava aberto e quem usa internet", Collector.hasUsageAccess(this)) { openUsageAccess() })
        val notifOk = Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        c2.addView(permRow("Notificações", "Alertas de calor e temperatura na barra", notifOk) { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1) })
        c2.addView(permRow("Sem restrição de bateria", "Para o sistema não fechar o monitor", ignoringBattery()) { openBatteryExemption() })
        c2.addView(permRow("Início automático (Xiaomi)", "Necessário no HyperOS para ligar ao reiniciar", null) { openAutostart() })
        c2.addView(permRow("Economia de bateria do app", "Em Bateria, escolha \"Sem restrições\"", null) { openAppDetails() })
        body.addView(c2)

        val c3 = Ui.card(this)
        c3.addView(Ui.title(this, "Dica para o Poco / HyperOS"))
        c3.addView(Ui.text(this, "O HyperOS fecha apps em segundo plano com muita força. Além das permissões acima, abra os apps recentes, segure o TermoVigia e toque no cadeado 🔒 para travá-lo. Assim o monitoramento não para.", 13.5f, Ui.MUTED))
        body.addView(c3)

        val c4 = Ui.card(this)
        c4.addView(Ui.title(this, "Como o app mede"))
        c4.addView(Ui.text(this, "• Temperatura: sensor da bateria (o mesmo que o sistema usa para proteger o aparelho).\n• Estado térmico e margem térmica: informados pelo próprio Android; quando chega em \"Grave\", o celular reduz a velocidade.\n• Consumo: corrente × tensão da bateria.\n• App culpado: qual app estava na tela enquanto a temperatura subia. O Android não permite a nenhum app comum medir o processador de cada app separadamente, então o resultado é uma estimativa — mas com algumas horas de uso fica bem claro quem esquenta.", 13f, Ui.MUTED))
        body.addView(c4)

        body.addView(Ui.button(this, "Apagar todo o histórico", false, Ui.RED) {
            confirm("Apagar todas as leituras e alertas?") { db.clearAll(); show(3) }
        })
        body.addView(Ui.text(this, "TermoVigia ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Throwable) { "" }}", 12f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })
    }

    private fun switchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        row.addView(Ui.text(this, label, 14f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, v -> onChange(v) } })
        return row
    }

    private fun permRow(label: String, desc: String, ok: Boolean?, action: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(Ui.text(this, when (ok) { true -> "✅ "; false -> "⚠️ "; null -> "" } + label, 14f, Ui.TEXT, true))
        col.addView(Ui.text(this, desc, 12.5f, Ui.MUTED))
        row.addView(col)
        if (ok != true) row.addView(Ui.text(this, "Abrir", 13.5f, Ui.ACCENT, true).apply {
            setPadding(dp(14), dp(8), dp(4), dp(8)); setOnClickListener { action() }
        })
        return row
    }

    // ------------------------------------------------------------------ ações

    private fun firstRun() {
        AlertDialog.Builder(this)
            .setTitle("Bem-vindo ao TermoVigia")
            .setMessage("Ele vai ficar medindo a temperatura, o consumo, o processador e qual app está aberto, o tempo todo em segundo plano.\n\nDepois de algumas horas de uso normal, a aba \"Causas\" mostra o que está esquentando o seu celular e se há algo fora do normal.\n\nPara funcionar direito, conceda as permissões que ele pedir.")
            .setPositiveButton("Ativar agora") { _, _ -> activate() }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun activate() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        MonitorService.start(this)
        main.postDelayed({ show(tab) }, 600)
        if (!Collector.hasUsageAccess(this)) {
            AlertDialog.Builder(this)
                .setTitle("Falta o \"Acesso ao uso\"")
                .setMessage("Sem essa permissão o app mede a temperatura, mas não consegue dizer qual aplicativo estava aberto quando esquentou.\n\nNa próxima tela, encontre o TermoVigia e ative \"Permitir acesso ao uso\".")
                .setPositiveButton("Abrir") { _, _ -> openUsageAccess() }
                .setNegativeButton("Agora não", null)
                .show()
        } else if (!ignoringBattery()) openBatteryExemption()
    }

    private fun ignoringBattery(): Boolean = try {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (e: Throwable) { true }

    private fun openUsageAccess() {
        if (!tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.parse("package:$packageName"))))
            if (!tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) openAppDetails()
    }

    private fun openBatteryExemption() {
        if (!tryStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))))
            tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openAutostart() {
        val opts = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
        )
        for (cn in opts) if (tryStart(Intent().setComponent(cn))) return
        openAppDetails()
    }

    private fun openAppDetails() {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
    }

    private fun tryStart(i: Intent): Boolean = try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Throwable) { false }

    private fun confirm(msg: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Sim") { _, _ -> yes() }.setNegativeButton("Cancelar", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        show(tab)
    }
}
