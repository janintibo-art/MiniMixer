package com.minimixer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Vumètre 2 colonnes de LED (vert / orange / rouge). */
class LedMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var level = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val cols = 2
        val rows = 8
        val cw = width / cols.toFloat()
        val ch = height / rows.toFloat()
        val lit = (level * rows + 0.5f).toInt()

        for (col in 0 until cols) {
            for (row in 0 until rows) {
                val fromBottom = rows - 1 - row
                val on = fromBottom < lit
                val base = when {
                    fromBottom >= rows - 2 -> Color.parseColor("#FF4B4B")
                    fromBottom >= rows - 4 -> Color.parseColor("#FFC24B")
                    else -> Color.parseColor("#9FE870")
                }
                paint.color = if (on) base
                else Color.argb(45, Color.red(base), Color.green(base), Color.blue(base))
                c.drawCircle(col * cw + cw / 2f, row * ch + ch / 2f, min(cw, ch) * 0.32f, paint)
            }
        }
    }
}
