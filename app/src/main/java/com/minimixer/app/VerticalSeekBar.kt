package com.minimixer.app

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

/** SeekBar vertical (fader de table de mixage). */
class VerticalSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatSeekBar(context, attrs, defStyle) {

    var onUserChange: ((Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(h, w, oldh, oldw)
    }

    @Synchronized
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onDraw(c: Canvas) {
        c.rotate(-90f)
        c.translate(-height.toFloat(), 0f)
        super.onDraw(c)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val p = (max - (max * event.y / height).toInt()).coerceIn(0, max)
                progress = p
                onUserChange?.invoke(p)
                onSizeChanged(width, height, 0, 0)
            }
        }
        return true
    }
}
