package io.github.threeinone.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * A rounded, animated on/off switch drawn by hand.
 *
 * The platform `Switch` cannot be restyled without a theme overlay, and the app ships
 * without AppCompat, so the control paints itself: a pill-shaped track with a white
 * thumb that slides between the two ends.
 */
class CardSwitch(context: Context) : View(context) {

    private val palette = Palette(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val trackWidth = dp(52f).toFloat()
    private val trackHeight = dp(32f).toFloat()
    private val thumbRadius = dp(13f).toFloat()
    private val inset = dp(3f).toFloat()

    private var thumbPosition = 0f
    private var animator: ValueAnimator? = null

    /** Whether the switch is on. */
    var checked: Boolean = false
        private set

    /** Fired when the user flips the switch. */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        minimumWidth = trackWidth.toInt()
        minimumHeight = trackHeight.toInt()
    }

    /** Sets the state without firing [onCheckedChange] — used while building the screen. */
    fun setCheckedSilently(value: Boolean) {
        checked = value
        thumbPosition = if (value) 1f else 0f
        invalidate()
    }

    private fun toggle() {
        val next = !checked
        checked = next
        animateTo(next)
        invalidate()
        onCheckedChange?.invoke(next)
    }

    private fun animateTo(target: Boolean) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(thumbPosition, if (target) 1f else 0f).apply {
            duration = 170
            addUpdateListener {
                thumbPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(trackWidth.toInt(), widthMeasureSpec),
            resolveSize(trackHeight.toInt(), heightMeasureSpec)
        )
    }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = (h - trackHeight) / 2f
        val radius = trackHeight / 2f

        paint.style = Paint.Style.FILL
        paint.color = if (checked) palette.accent else palette.surfaceAlt
        canvas.drawRoundRect(inset, top, w - inset, top + trackHeight, radius, radius, paint)

        if (!checked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = palette.stroke
            canvas.drawRoundRect(inset, top, w - inset, top + trackHeight, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }

        val travel = w - 2 * inset - 2 * thumbRadius
        val cx = inset + thumbRadius + thumbPosition * travel
        paint.color = Color.WHITE
        canvas.drawCircle(cx, h / 2f, thumbRadius, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        toggle()
        return true
    }
}

/** A small filled circle used as a status marker. */
class StatusDot(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = Palette(context)

    /** The dot's colour; the default is the muted "idle" grey. */
    var color: Int = palette.muted
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(8f)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
    }
}

/** A circular colour swatch. */
class ColorDot(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = Palette(context)

    /** The swatch colour. */
    var color: Int = Color.GRAY
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) / 2f
        paint.color = color
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (radius * 0.18f).coerceAtLeast(1f)
        paint.color = palette.stroke
        canvas.drawCircle(width / 2f, height / 2f, radius - paint.strokeWidth / 2f, paint)
        paint.style = Paint.Style.FILL
    }
}

/**
 * A selectable chip, used to pick the previewed state.
 *
 * The chip paints its own background so the selected and unselected states can differ
 * in more than just the text colour.
 */
fun Context.chip(text: String, selected: Boolean, palette: Palette): TextView {
    val horizontal = dp(14)
    val vertical = dp(7)
    return TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(if (selected) palette.onAccent else palette.muted)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = if (selected) pill(palette.accent) else pill(palette.surfaceAlt, palette.stroke)
        setPadding(horizontal, vertical, horizontal, vertical)
        isClickable = true
        isFocusable = true
    }
}

/** A hairline separator for use inside a card. */
fun Context.divider(palette: Palette): View = View(this).apply {
    setBackgroundColor(palette.stroke)
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
}
