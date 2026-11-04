package com.termovigia.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Cores e peças de interface (o app não usa XML de layout). */
object Ui {
    val BG = 0xFF0E1014.toInt()
    val CARD = 0xFF181B22.toInt()
    val CARD2 = 0xFF222631.toInt()
    val LINE = 0xFF2A2F3A.toInt()
    val TEXT = 0xFFEDEFF3.toInt()
    val MUTED = 0xFF98A1B0.toInt()
    val ACCENT = 0xFF4DA3FF.toInt()
    val GREEN = 0xFF34C77B.toInt()
    val YELLOW = 0xFFF5C542.toInt()
    val ORANGE = 0xFFFF9533.toInt()
    val RED = 0xFFFF5252.toInt()
    val BLUE_SOFT = 0x884DA3FF.toInt()
    val GRAY_SOFT = 0x66A0A8B8.toInt()

    fun tempColor(t: Float) = when {
        t < 0f -> MUTED
        t < 38f -> GREEN
        t < 41f -> YELLOW
        t < 44f -> ORANGE
        else -> RED
    }

    fun levelColor(l: Int) = when (l) { 2 -> RED; 1 -> ORANGE; else -> GREEN }

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun bg(color: Int, radiusDp: Int, c: Context, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(c, 1), stroke)
    }

    fun text(c: Context, s: CharSequence, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
        }

    fun card(c: Context, color: Int = CARD): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(color, 18, c)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(c, 12) }
    }

    fun title(c: Context, s: String) = text(c, s, 15.5f, TEXT, true).apply { setPadding(0, 0, 0, dp(c, 8)) }

    fun button(c: Context, s: String, primary: Boolean = true, color: Int = ACCENT, onClick: () -> Unit): TextView =
        text(c, s, 15f, if (primary) 0xFF0B0D10.toInt() else color, true).apply {
            gravity = Gravity.CENTER
            background = if (primary) bg(color, 14, c) else bg(0, 14, c, color)
            setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, 8) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun chips(c: Context, options: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, value) in options) {
            val on = value == selected
            row.addView(text(c, label, 13.5f, if (on) 0xFF0B0D10.toInt() else TEXT, on).apply {
                background = if (on) bg(ACCENT, 20, c) else bg(CARD2, 20, c)
                setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(c, 8) }
                setOnClickListener { onPick(value) }
            })
        }
        return HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(c, 10) }
        }
    }

    /** Linha "rótulo ......... valor". */
    fun kv(c: Context, k: String, v: String, vColor: Int = TEXT): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(c, 5), 0, dp(c, 5))
        addView(text(c, k, 13.5f, MUTED).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        addView(text(c, v, 13.5f, vColor, true).apply { gravity = Gravity.END })
    }

    /** Barra horizontal de proporção. */
    fun bar(c: Context, fraction: Float, color: Int): View {
        val outer = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(CARD2, 4, c)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 6)).apply { topMargin = dp(c, 6) }
        }
        val f = fraction.coerceIn(0.03f, 1f)
        outer.addView(View(c).apply { background = bg(color, 4, c); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f) })
        outer.addView(View(c).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - f + 0.0001f) })
        return outer
    }

    /** Cartão de achado com faixa colorida à esquerda. */
    fun finding(c: Context, f: Finding): View {
        val wrap = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(CARD, 16, c)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(c, 10) }
        }
        wrap.addView(View(c).apply { background = bg(levelColor(f.level), 3, c); layoutParams = LinearLayout.LayoutParams(dp(c, 5), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(c, 8), dp(c, 12), 0, dp(c, 12)) } })
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 12), dp(c, 12), dp(c, 14), dp(c, 12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(text(c, f.title, 14.5f, TEXT, true))
        col.addView(text(c, f.text, 13.5f, MUTED).apply { setPadding(0, dp(c, 4), 0, 0) })
        wrap.addView(col)
        return wrap
    }
}
