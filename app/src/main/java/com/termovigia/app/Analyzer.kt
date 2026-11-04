package com.termovigia.app

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import kotlin.math.max

data class AppStat(
    val pkg: String, val label: String, val minutes: Float, val avgT: Float, val maxT: Float,
    val risePer10: Float, val avgW: Float, val avgCpu: Int, val score: Float
)

/** level: 0 = tudo certo, 1 = atenção, 2 = problema */
data class Finding(val level: Int, val title: String, val text: String)

data class DataUse(val label: String, val bytes: Long)

data class Report(
    val hours: Int, val samples: Int, val coveredMin: Float,
    val maxT: Float, val maxTs: Long, val avgT: Float, val minAboveAlert: Float,
    val findings: List<Finding>, val apps: List<AppStat>, val data: List<DataUse>
)

object Analyzer {

    /** Intervalo máximo entre duas leituras para considerar que são seguidas. */
    private fun gapLimit(c: Context) = max(Prefs.interval(c), 60) * 3_000L

    fun analyze(c: Context, hours: Int): Report {
        val now = System.currentTimeMillis()
        val start = now - hours * 3_600_000L
        val s = Db.get(c).samples(start)
        val alert = Prefs.alertTemp(c)
        val gap = gapLimit(c)
        val findings = ArrayList<Finding>()

        if (s.size < 4) {
            findings.add(Finding(1, "Ainda coletando dados", "Deixe o monitoramento ligado e use o celular normalmente. Em uns 30 minutos já aparece um diagnóstico; com um dia inteiro fica bem mais preciso."))
            return Report(hours, s.size, 0f, -1f, 0L, -1f, 0f, findings, emptyList(), dataUsage(c, start, now))
        }

        // ---- números gerais
        var covered = 0f; var hotMin = 0f
        val maxS = s.maxByOrNull { it.batTemp }!!
        val apps = HashMap<String, Acc>()
        var offMin = 0f; var offDrop = 0f; var offWs = 0f; var offWn = 0; var offRise = 0f
        var chgMax = -1f; var chgUseMin = 0f; var chgUseTSum = 0f; var chgUseN = 0
        var weakMin = 0f; var weakTSum = 0f; var weakN = 0; var otherTSum = 0f; var otherN = 0
        var brightMin = 0f; var throttleMin = 0f; var cpuHighMin = 0f

        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            val dtMs = b.ts - a.ts
            if (dtMs <= 0 || dtMs > gap) continue
            val dt = dtMs / 60_000f
            covered += dt
            if (a.batTemp >= alert) hotMin += dt
            if (a.thermal >= 3) throttleMin += dt
            if (a.screenOn && a.brightness >= 85) brightMin += dt
            if (a.cpuPct >= 75) cpuHighMin += dt

            if (a.charging) {
                chgMax = max(chgMax, a.batTemp)
                if (a.screenOn) { chgUseMin += dt; chgUseTSum += a.batTemp; chgUseN++ }
            }
            if (!a.screenOn && !a.charging && !b.charging) {
                offMin += dt
                if (b.level in 0..a.level) offDrop += (a.level - b.level)
                if (a.watts > 0) { offWs += a.watts; offWn++ }
                offRise += (b.batTemp - a.batTemp)
            }
            if (a.net == "mobile" && a.signal in 0..1) { weakMin += dt; weakTSum += a.batTemp; weakN++ } else if (a.screenOn) { otherTSum += a.batTemp; otherN++ }

            if (a.screenOn && a.app.isNotEmpty() && !a.charging) {
                val acc = apps.getOrPut(a.app) { Acc() }
                acc.min += dt; acc.rise += (b.batTemp - a.batTemp); acc.tSum += a.batTemp; acc.n++
                acc.maxT = max(acc.maxT, a.batTemp)
                if (a.watts > 0) { acc.wSum += a.watts; acc.wN++ }
                if (a.cpuPct >= 0) { acc.cpuSum += a.cpuPct; acc.cpuN++ }
            }
        }

        val avgT = s.map { it.batTemp }.average().toFloat()
        val base = s.map { it.batTemp }.sorted()[s.size / 10]

        val stats = apps.filter { it.value.min >= 3f }.map { (pkg, a) ->
            val avg = a.tSum / a.n
            val rise = a.rise / a.min * 10f
            val w = if (a.wN > 0) a.wSum / a.wN else -1f
            val score = (avg - base) + rise * 1.5f + (if (w > 0) w * 0.4f else 0f)
            AppStat(pkg, Names.label(c, pkg), a.min, avg, a.maxT, rise, w, if (a.cpuN > 0) a.cpuSum / a.cpuN else -1, score)
        }.sortedByDescending { it.score }

        // ---- diagnóstico geral
        when {
            maxS.batTemp >= 45f -> findings.add(Finding(2, "Chegou a ${F.t(maxS.batTemp)}", "Às ${F.dateTime(maxS.ts)} a bateria chegou a uma temperatura alta demais. Acima de 45 °C a bateria envelhece mais rápido e o sistema começa a limitar o desempenho. ${whatAt(c, maxS)}"))
            maxS.batTemp >= alert -> findings.add(Finding(1, "Pico de ${F.t(maxS.batTemp)}", "Às ${F.dateTime(maxS.ts)} passou do seu limite de alerta ($alert °C). ${whatAt(c, maxS)}"))
            else -> findings.add(Finding(0, "Temperatura dentro do normal", "A máxima no período foi ${F.t(maxS.batTemp)} e a média ${F.t(avgT)}. Para celular em uso, até uns 40 °C na bateria é normal."))
        }
        if (hotMin >= 10f) findings.add(Finding(if (hotMin >= 60f) 2 else 1, "Ficou quente por ${F.mins(hotMin)}", "Tempo acima de $alert °C no período. Aquecimento curto (jogo, vídeo, câmera) é normal; ficar quente por muito tempo não é."))

        // carregando
        if (chgMax >= 43f) {
            val extra = if (chgUseN > 0 && chgUseMin >= 10f) " Você usou o celular por ${F.mins(chgUseMin)} enquanto carregava (média ${F.t(chgUseTSum / chgUseN)}): usar e carregar ao mesmo tempo é uma das maiores causas de calor." else ""
            findings.add(Finding(if (chgMax >= 45f) 2 else 1, "Esquenta carregando (até ${F.t(chgMax)})", "O carregamento turbo gera muito calor.$extra Dica: carregue sem capinha, em superfície dura e, se possível, ative o carregamento otimizado/inteligente nas configurações de bateria."))
        }

        // tela desligada
        if (offMin >= 30f) {
            val perH = offDrop / (offMin / 60f)
            val w = if (offWn > 0) offWs / offWn else -1f
            if (perH >= 2.5f || offRise >= 3f) {
                val top = dataUsage(c, start, now).take(3).joinToString(", ") { "${it.label} (${F.bytes(it.bytes)})" }
                findings.add(Finding(2, "Gasta bateria com a tela desligada", "Com a tela apagada o celular perdeu ${F.d1(perH)}% por hora${if (w > 0) " (média ${F.w(w)})" else ""}. O normal é menos de 1–2%/h. Isso indica algum app trabalhando em segundo plano.${if (top.isNotEmpty()) " Quem mais usou internet no período: $top." else ""} Veja a lista \"Uso de internet\" abaixo."))
            } else {
                findings.add(Finding(0, "Em repouso está normal", "Com a tela desligada perdeu ${F.d1(perH)}% por hora — dentro do esperado."))
            }
        }

        if (throttleMin >= 2f) findings.add(Finding(2, "O sistema limitou o desempenho", "Por ${F.mins(throttleMin)} o próprio Android marcou o aparelho em estado térmico grave e reduziu a velocidade para esfriar. É aí que o celular trava e fica lento."))

        if (weakN >= 10 && otherN >= 10 && weakTSum / weakN - otherTSum / otherN >= 1.5f)
            findings.add(Finding(1, "Sinal fraco esquenta", "Com dados móveis e sinal fraco (${F.mins(weakMin)}) a temperatura média foi ${F.t(weakTSum / weakN)}, contra ${F.t(otherTSum / otherN)} no resto do tempo. O modem força para manter a conexão. Use Wi-Fi quando puder."))

        if (brightMin >= 30f) findings.add(Finding(1, "Brilho alto por ${F.mins(brightMin)}", "Tela no brilho máximo (ou perto) consome muito e aquece, principalmente ao sol."))

        if (cpuHighMin >= 30f) findings.add(Finding(1, "Processador acelerado por ${F.mins(cpuHighMin)}", "Os núcleos ficaram perto da velocidade máxima por bastante tempo. Jogos, gravação de vídeo e apps pesados fazem isso."))

        stats.firstOrNull()?.let { top ->
            if (top.risePer10 >= 1f || top.avgT >= alert - 1)
                findings.add(Finding(1, "Maior suspeito: ${top.label}", "Com ele na tela a temperatura média foi ${F.t(top.avgT)} (máx. ${F.t(top.maxT)}) e subiu em média ${F.d1(top.risePer10)} °C a cada 10 min${if (top.avgW > 0) ", consumindo ${F.w(top.avgW)}" else ""}."))
        }

        if (findings.none { it.level > 0 }) findings.add(Finding(0, "Nenhuma anomalia encontrada", "Pelo que foi medido, o aquecimento está dentro do esperado para o uso que você fez."))

        return Report(hours, s.size, covered, maxS.batTemp, maxS.ts, avgT, hotMin, findings.sortedByDescending { it.level }, stats, dataUsage(c, start, now))
    }

    private class Acc {
        var min = 0f; var rise = 0f; var tSum = 0f; var n = 0; var maxT = -1f
        var wSum = 0f; var wN = 0; var cpuSum = 0; var cpuN = 0
    }

    private fun whatAt(c: Context, s: Sample): String {
        val parts = ArrayList<String>()
        if (s.screenOn && s.app.isNotEmpty()) parts.add("app na tela: ${Names.label(c, s.app)}")
        if (!s.screenOn) parts.add("tela desligada (algo em segundo plano)")
        if (s.charging) parts.add("carregando")
        return if (parts.isEmpty()) "" else "Nesse momento: " + parts.joinToString(", ") + "."
    }

    /** Possíveis motivos do calor agora. */
    fun liveCauses(c: Context, s: Sample, recent: List<Sample>): List<String> {
        val out = ArrayList<String>()
        if (s.charging) out.add(if (s.screenOn) "Usando enquanto carrega — calor do carregador + uso somados" else "Carregando (carga rápida aquece a bateria)")
        if (s.thermal >= 3) out.add("O sistema já está limitando o desempenho para esfriar")
        if (s.cpuPct >= 70) out.add("Processador em ${s.cpuPct}% da velocidade máxima")
        val w = s.watts
        if (!s.charging && w >= 5f) out.add("Consumo alto: ${F.w(w)}")
        if (s.screenOn && s.brightness >= 85) out.add("Brilho da tela em ${s.brightness}%")
        if (s.net == "mobile" && s.signal in 0..1) out.add("Dados móveis com sinal fraco (modem forçando)")
        if (!s.screenOn && !s.charging && w >= 1f) out.add("Tela desligada mas gastando ${F.w(w)} — app em segundo plano")
        suspect(c, recent)?.let { if (s.screenOn) out.add("App mais usado nos últimos minutos: $it") }
        return out
    }

    /** App que mais ficou na tela nas leituras recebidas (ignora este app). */
    fun suspect(c: Context, recent: List<Sample>): String? =
        recent.filter { it.screenOn && it.app.isNotEmpty() && it.app != c.packageName }
            .groupingBy { it.app }.eachCount().maxByOrNull { it.value }?.key?.let { Names.label(c, it) }

    /** Quem mais usou internet (Wi-Fi + dados) no período — app trabalhando escondido aparece aqui. */
    fun dataUsage(c: Context, start: Long, end: Long): List<DataUse> {
        if (!Collector.hasUsageAccess(c)) return emptyList()
        val nsm = c.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return emptyList()
        val byUid = HashMap<Int, Long>()
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                val st = nsm.querySummary(type, null, start, end)
                val b = NetworkStats.Bucket()
                while (st.hasNextBucket()) {
                    st.getNextBucket(b)
                    byUid[b.uid] = (byUid[b.uid] ?: 0L) + b.rxBytes + b.txBytes
                }
                st.close()
            } catch (e: Throwable) {}
        }
        val pm = c.packageManager
        val byLabel = HashMap<String, Long>()
        for ((uid, bytes) in byUid) {
            val label = when (uid) {
                -5 -> "Roteador / compartilhar internet"
                -4 -> "Apps desinstalados"
                1000 -> "Sistema Android"
                0 -> "Sistema (root)"
                else -> pm.getPackagesForUid(uid)?.firstOrNull()?.let { Names.label(c, it) } ?: "App uid $uid"
            }
            byLabel[label] = (byLabel[label] ?: 0L) + bytes
        }
        return byLabel.filter { it.value > 512 * 1024 }.map { DataUse(it.key, it.value) }.sortedByDescending { it.bytes }.take(10)
    }

    fun reportText(c: Context, r: Report): String {
        val sb = StringBuilder()
        sb.append("TermoVigia — relatório das últimas ${r.hours}h\n")
        sb.append("Monitorado: ${F.mins(r.coveredMin)} • Máxima: ${F.t(r.maxT)} • Média: ${F.t(r.avgT)}\n\n")
        sb.append("DIAGNÓSTICO\n")
        for (f in r.findings) sb.append(when (f.level) { 2 -> "🔴 "; 1 -> "🟡 "; else -> "🟢 " }).append(f.title).append("\n   ").append(f.text).append("\n")
        if (r.apps.isNotEmpty()) {
            sb.append("\nAPPS QUE MAIS ESQUENTAM\n")
            r.apps.take(10).forEachIndexed { i, a ->
                sb.append("${i + 1}. ${a.label} — média ${F.t(a.avgT)}, máx ${F.t(a.maxT)}, +${F.d1(a.risePer10)} °C/10min, ${F.mins(a.minutes)} de uso\n")
            }
        }
        if (r.data.isNotEmpty()) {
            sb.append("\nUSO DE INTERNET\n")
            r.data.forEach { sb.append("• ${it.label}: ${F.bytes(it.bytes)}\n") }
        }
        return sb.toString()
    }
}
