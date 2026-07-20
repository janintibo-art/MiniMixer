package com.minimixer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Potentiomètre rotatif : bezel chromé, métal, reflet, glow.
 * Haptique crantée pendant la rotation, double-tap = retour à la valeur par défaut.
 */
class KnobView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var max = 100
    var value = 0
        set(v) {
            field = v.coerceIn(0, max)
            invalidate()
        }
    var defaultValue = 0
    var onChange: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastY = 0f
    private var acc = 0f
    private var lastTapTime = 0L
    private var lastBucket = -1

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isHapticFeedbackEnabled = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 280) {
                    // Double-tap : retour à la valeur par défaut
                    value = defaultValue
                    onChange?.invoke(value)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    lastTapTime = 0
                } else {
                    lastTapTime = now
                }
                lastY = e.y
                acc = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                acc += (lastY - e.y) / height.coerceAtLeast(1) * max * 1.6f
                lastY = e.y
                val d = acc.toInt()
                if (d != 0) {
                    acc -= d
                    val before = value
                    value += d
                    if (value != before) {
                        onChange?.invoke(value)
                        tickHaptics()
                    }
                }
            }
        }
        return true
    }

    /** Vibration crantée : petit tick tous les 5 %, plus marqué au centre et en butée. */
    private fun tickHaptics() {
        val m = max.coerceAtLeast(1)
        when {
            value == 0 || value == m ->
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            value == defaultValue && defaultValue > 0 ->
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            else -> {
                val bucket = value * 20 / m
                if (bucket != lastBucket) {
                    lastBucket = bucket
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.66f
        val m = max.coerceAtLeast(1)

        // Petites graduations autour
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = r * 0.035f
        paint.color = Color.parseColor("#4A4A54")
        for (i in 0..10) {
            val a = Math.toRadians(135.0 + 27.0 * i)
            val cs = cos(a).toFloat()
            val sn = sin(a).toFloat()
            c.drawLine(cx + r * 1.16f * cs, cy + r * 1.16f * sn, cx + r * 1.26f * cs, cy + r * 1.26f * sn, paint)
        }

        // Arc de valeur avec glow
        paint.strokeCap = Paint.Cap.ROUND
        val arcRect = RectF(cx - r * 1.34f, cy - r * 1.34f, cx + r * 1.34f, cy + r * 1.34f)
        paint.strokeWidth = r * 0.09f
        paint.color = Color.parseColor("#2A2A31")
        c.drawArc(arcRect, 135f, 270f, false, paint)
        paint.color = Mix.accent
        paint.setShadowLayer(r * 0.20f, 0f, 0f, Mix.accent)
        c.drawArc(arcRect, 135f, 270f * value / m, false, paint)
        paint.clearShadowLayer()

        // Ombre portée
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#B3000000")
        c.drawCircle(cx + r * 0.05f, cy + r * 0.13f, r * 1.05f, paint)

        // Bezel chromé
        paint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            Color.parseColor("#84848F"), Color.parseColor("#08080A"), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * 1.05f, paint)
        paint.shader = null

        // Corps métal
        paint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.42f, r * 1.9f,
            intArrayOf(
                Color.parseColor("#70707B"),
                Color.parseColor("#33333B"),
                Color.parseColor("#0F0F12")
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * 0.96f, paint)
        paint.shader = null

        // Stries fines
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#CC060608")
        paint.strokeWidth = r * 0.045f
        var a = 0.0
        while (a < 360.0) {
            val rad = Math.toRadians(a)
            val cs = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            c.drawLine(cx + r * 0.80f * cs, cy + r * 0.80f * sn, cx + r * 0.95f * cs, cy + r * 0.95f * sn, paint)
            a += 10.0
        }

        // Capuchon central
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - r * 0.18f, cy - r * 0.26f, r * 0.95f,
            intArrayOf(
                Color.parseColor("#4B4B55"),
                Color.parseColor("#1C1C21"),
                Color.parseColor("#0F0F12")
            ),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * 0.58f, paint)
        paint.shader = null

        // Reflet spéculaire
        paint.color = Color.argb(40, 255, 255, 255)
        c.drawOval(RectF(cx - r * 0.52f, cy - r * 0.64f, cx + r * 0.14f, cy - r * 0.10f), paint)

        // Index néon
        val ang = Math.toRadians(135.0 + 270.0 * value / m)
        val cs = cos(ang).toFloat()
        val sn = sin(ang).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.12f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Mix.accent
        paint.setShadowLayer(r * 0.22f, 0f, 0f, Mix.accent)
        c.drawLine(cx + r * 0.20f * cs, cy + r * 0.20f * sn, cx + r * 0.72f * cs, cy + r * 0.72f * sn, paint)
        paint.clearShadowLayer()
    }
}
