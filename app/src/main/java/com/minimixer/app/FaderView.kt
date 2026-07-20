package com.minimixer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Fader de console dessiné sur mesure : glissière creusée, graduations,
 * capuchon métallique avec ombre, rainures et ligne lumineuse.
 */
class FaderView(
    context: Context,
    private val horizontal: Boolean = false
) : View(context) {

    var max = 100
        set(v) {
            field = v.coerceAtLeast(1)
            invalidate()
        }
    var value = 0
        set(v) {
            field = v.coerceIn(0, max)
            invalidate()
        }
    var onChange: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun capAlong(): Float = min(width, height) * 0.60f
    private fun inset(): Float = capAlong() / 2f + 8f
    private fun span(): Float =
        ((if (horizontal) width else height) - 2f * inset()).coerceAtLeast(1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> {
                if (e.action == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val pos = (if (horizontal) e.x else e.y) - inset()
                val t = (pos / span()).coerceIn(0f, 1f)
                val p = if (horizontal) (t * max + 0.5f).toInt()
                else ((1f - t) * max + 0.5f).toInt()
                if (p != value) {
                    value = p
                    onChange?.invoke(p)
                }
                return true
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (horizontal) drawHorizontal(c) else drawVertical(c)
    }

    // ---------------- Vertical ----------------
    private fun drawVertical(c: Canvas) {
        val cx = width / 2f
        val top = inset()
        val bottom = height - inset()

        // Graduations latérales
        paint.style = Paint.Style.STROKE
        for (i in 0..10) {
            val y = top + (bottom - top) * i / 10f
            val long = i % 5 == 0
            paint.strokeWidth = if (long) 2.5f else 1.5f
            paint.color = if (long) Color.parseColor("#4E4E58") else Color.parseColor("#32323A")
            val len = if (long) width * 0.18f else width * 0.11f
            c.drawLine(cx - width * 0.15f - len, y, cx - width * 0.15f, y, paint)
            c.drawLine(cx + width * 0.15f, y, cx + width * 0.15f + len, y, paint)
        }

        // Glissière creusée
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#040405")
        paint.setShadowLayer(5f, 0f, 2f, Color.BLACK)
        val rail = RectF(cx - 5.5f, top, cx + 5.5f, bottom)
        c.drawRoundRect(rail, 6f, 6f, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.parseColor("#30303A")
        c.drawRoundRect(rail, 6f, 6f, paint)

        // Capuchon
        val t = 1f - value.toFloat() / max
        val pos = top + (bottom - top) * t
        drawCap(c, RectF(cx - width * 0.42f, pos - capAlong() / 2f, cx + width * 0.42f, pos + capAlong() / 2f), pos, vertical = true)
    }

    // ---------------- Horizontal ----------------
    private fun drawHorizontal(c: Canvas) {
        val cy = height / 2f
        val left = inset()
        val right = width - inset()

        paint.style = Paint.Style.STROKE
        for (i in 0..10) {
            val x = left + (right - left) * i / 10f
            val long = i % 5 == 0
            paint.strokeWidth = if (long) 2.5f else 1.5f
            paint.color = if (long) Color.parseColor("#4E4E58") else Color.parseColor("#32323A")
            val len = if (long) height * 0.18f else height * 0.11f
            c.drawLine(x, cy - height * 0.15f - len, x, cy - height * 0.15f, paint)
            c.drawLine(x, cy + height * 0.15f, x, cy + height * 0.15f + len, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#040405")
        paint.setShadowLayer(5f, 0f, 2f, Color.BLACK)
        val rail = RectF(left, cy - 5.5f, right, cy + 5.5f)
        c.drawRoundRect(rail, 6f, 6f, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.parseColor("#30303A")
        c.drawRoundRect(rail, 6f, 6f, paint)

        val t = value.toFloat() / max
        val pos = left + (right - left) * t
        drawCap(c, RectF(pos - capAlong() / 2f, cy - height * 0.42f, pos + capAlong() / 2f, cy + height * 0.42f), pos, vertical = false)
    }

    // ---------------- Capuchon métallique ----------------
    private fun drawCap(c: Canvas, cap: RectF, pos: Float, vertical: Boolean) {
        paint.style = Paint.Style.FILL
        paint.setShadowLayer(11f, 0f, 5f, Color.parseColor("#D9000000"))
        paint.shader = if (vertical)
            LinearGradient(
                0f, cap.top, 0f, cap.bottom,
                intArrayOf(
                    Color.parseColor("#8E8E99"),
                    Color.parseColor("#585862"),
                    Color.parseColor("#26262C"),
                    Color.parseColor("#0D0D10")
                ),
                floatArrayOf(0f, 0.35f, 0.72f, 1f), Shader.TileMode.CLAMP
            )
        else
            LinearGradient(
                cap.left, 0f, cap.right, 0f,
                intArrayOf(
                    Color.parseColor("#8E8E99"),
                    Color.parseColor("#585862"),
                    Color.parseColor("#26262C"),
                    Color.parseColor("#0D0D10")
                ),
                floatArrayOf(0f, 0.35f, 0.72f, 1f), Shader.TileMode.CLAMP
            )
        c.drawRoundRect(cap, 7f, 7f, paint)
        paint.shader = null
        paint.clearShadowLayer()

        // Rainures
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.parseColor("#A6000000")
        if (vertical) {
            val g1 = cap.top + cap.height() * 0.24f
            val g2 = cap.bottom - cap.height() * 0.24f
            c.drawLine(cap.left + 5f, g1, cap.right - 5f, g1, paint)
            c.drawLine(cap.left + 5f, g2, cap.right - 5f, g2, paint)
        } else {
            val g1 = cap.left + cap.width() * 0.24f
            val g2 = cap.right - cap.width() * 0.24f
            c.drawLine(g1, cap.top + 5f, g1, cap.bottom - 5f, paint)
            c.drawLine(g2, cap.top + 5f, g2, cap.bottom - 5f, paint)
        }

        // Ligne centrale lumineuse
        paint.strokeWidth = 3f
        paint.color = Color.parseColor("#F4F4F8")
        paint.setShadowLayer(7f, 0f, 0f, Color.parseColor("#9FE870"))
        if (vertical) c.drawLine(cap.left + 4f, pos, cap.right - 4f, pos, paint)
        else c.drawLine(pos, cap.top + 4f, pos, cap.bottom - 4f, paint)
        paint.clearShadowLayer()
    }
}
