package com.termovigia.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Gráfico da temperatura ao longo do tempo, com faixas de "carregando" e "tela desligada". */
class ChartView(c: Context) : View(c) {
    private var data: List<Sample> = emptyList()
    private var from = 0L
    private var to = 0L
    private var alert = 42
    private var gap = 180_000L
    private val d = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE; strokeWidth = 1f * d }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = 10.5f * d }
    private val band = Paint()

    fun set(samples: List<Sample>, hours: Int, alertTemp: Int, intervalSec: Int) {
        data = samples
        to = System.currentTimeMillis()
        from = to - hours * 3_600_000L
        alert = alertTemp
        gap = max(intervalSec, 60) * 3_000L
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val left = 34 * d; val right = w - 8 * d; val top = 8 * d; val bottom = h - 34 * d
        if (data.size < 2) {
            txt.textAlign = Paint.Align.CENTER
            cv.drawText("Sem dados neste período ainda", w / 2, h / 2, txt)
            txt.textAlign = Paint.Align.LEFT
            return
        }
        var lo = floor(min(30f, data.minOf { it.batTemp } - 1f))
        var hi = ceil(max(alert + 3f, data.maxOf { it.batTemp } + 1f))
        if (hi - lo < 8) hi = lo + 8
        fun x(t: Long) = left + (t - from).toFloat() / (to - from) * (right - left)
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)

        // faixas: carregando (azul) e tela desligada (cinza) na base
        for (i in 1 until data.size) {
            val a = data[i - 1]; val b = data[i]
            if (b.ts - a.ts > gap) continue
            val x1 = x(a.ts); val x2 = x(b.ts)
            if (a.charging) { band.color = Ui.BLUE_SOFT; cv.drawRect(x1, bottom + 3 * d, x2, bottom + 8 * d, band) }
            if (!a.screenOn) { band.color = Ui.GRAY_SOFT; cv.drawRect(x1, bottom + 9 * d, x2, bottom + 14 * d, band) }
        }

        // grade
        val step = if (hi - lo > 16) 4 else 2
        var v = (ceil(lo / step) * step).toInt()
        while (v <= hi) {
            val yy = y(v.toFloat())
            cv.drawLine(left, yy, right, yy, grid)
            cv.drawText("$v°", 2 * d, yy + 4 * d, txt)
            v += step
        }
        dash.color = Ui.ORANGE; cv.drawLine(left, y(alert.toFloat()), right, y(alert.toFloat()), dash)
        if (45f in lo..hi) { dash.color = Ui.RED; cv.drawLine(left, y(45f), right, y(45f), dash) }

        // linha colorida conforme a temperatura
        for (i in 1 until data.size) {
            val a = data[i - 1]; val b = data[i]
            if (b.ts - a.ts > gap) continue
            line.color = Ui.tempColor(max(a.batTemp, b.batTemp))
            val p = Path(); p.moveTo(x(a.ts), y(a.batTemp)); p.lineTo(x(b.ts), y(b.batTemp))
            cv.drawPath(p, line)
        }

        // horários
        val ty = h - 4 * d
        cv.drawText(F.time(from), left, ty, txt)
        txt.textAlign = Paint.Align.CENTER; cv.drawText(F.time((from + to) / 2), (left + right) / 2, ty, txt)
        txt.textAlign = Paint.Align.RIGHT; cv.drawText("agora", right, ty, txt)
        txt.textAlign = Paint.Align.LEFT
    }
}
