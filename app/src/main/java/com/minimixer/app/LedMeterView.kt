package com.minimixer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Vumètre LED avec halo lumineux quand les segments s'allument. */
class LedMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var level = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

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
                    else -> Mix.accent
                }
                val x = col * cw
                val y = row * ch
                val rect = RectF(x + cw * 0.16f, y + ch * 0.22f, x + cw * 0.84f, y + ch * 0.78f)
                if (on) {
                    paint.color = base
                    paint.setShadowLayer(6f, 0f, 0f, base)
                } else {
                    paint.color = Color.argb(42, Color.red(base), Color.green(base), Color.blue(base))
                }
                c.drawRoundRect(rect, 3f, 3f, paint)
                paint.clearShadowLayer()
            }
        }
    }
}
