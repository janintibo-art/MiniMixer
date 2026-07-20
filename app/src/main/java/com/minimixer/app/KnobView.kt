package com.minimixer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Potentiomètre rotatif façon table de mixage (glisser vers le haut/bas pour tourner). */
class KnobView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var max = 100
    var value = 0
        set(v) {
            field = v.coerceIn(0, max)
            invalidate()
        }
    var onChange: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastY = 0f
    private var acc = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                acc += (lastY - e.y) / height.coerceAtLeast(1) * max * 1.6f
                lastY = e.y
                val d = acc.toInt()
                if (d != 0) {
                    acc -= d
                    value += d
                    onChange?.invoke(value)
                }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.72f

        // Arc gradué (fond + valeur)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = r * 0.10f
        val rect = RectF(cx - r * 1.28f, cy - r * 1.28f, cx + r * 1.28f, cy + r * 1.28f)
        paint.color = Color.parseColor("#33333A")
        c.drawArc(rect, 135f, 270f, false, paint)
        paint.color = Color.parseColor("#9FE870")
        c.drawArc(rect, 135f, 270f * value / max.coerceAtLeast(1), false, paint)

        // Ombre portée
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#99000000")
        c.drawCircle(cx, cy + r * 0.10f, r * 1.02f, paint)

        // Corps du bouton (métal brossé)
        paint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.35f, r * 1.7f,
            Color.parseColor("#5E5E66"), Color.parseColor("#101013"), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // Stries du bord
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#0B0B0D")
        paint.strokeWidth = r * 0.055f
        var a = 0.0
        while (a < 360.0) {
            val rad = Math.toRadians(a)
            val cs = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            c.drawLine(cx + r * 0.84f * cs, cy + r * 0.84f * sn, cx + r * 0.98f * cs, cy + r * 0.98f * sn, paint)
            a += 15.0
        }

        // Capuchon central
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - r * 0.2f, cy - r * 0.25f, r,
            Color.parseColor("#3C3C44"), Color.parseColor("#141417"), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * 0.60f, paint)
        paint.shader = null

        // Index lumineux
        val ang = Math.toRadians(135.0 + 270.0 * value / max.coerceAtLeast(1))
        val cs = cos(ang).toFloat()
        val sn = sin(ang).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.13f
        paint.color = Color.parseColor("#9FE870")
        c.drawLine(cx + r * 0.22f * cs, cy + r * 0.22f * sn, cx + r * 0.78f * cs, cy + r * 0.78f * sn, paint)
    }
}
