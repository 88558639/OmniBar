package io.github.threeinone.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.IndicatorState
import kotlin.math.min

class IndicatorView(context: Context) : View(context) {
    private val renderer = IndicatorRenderer()
    private var aligned = false

    /**
     * Height of the status bar itself.
     *
     * The battery view the indicator lives in is parked at the bottom of the bar, so the
     * content ends up in the lower half of it. The bar height is the reference the content
     * has to be aligned to.
     */
    private val statusBarHeight: Int
        get() {
            val insets = rootWindowInsets
            val cutout = insets?.displayCutout?.safeInsetTop ?: 0
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            } else 0
            val measured = maxOf(cutout, bars)
            if (measured > 0) return measured
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else height
        }

    /**
     * Places the indicator inside the status bar.
     *
     * The view is exactly as tall as its content, so the content cannot be moved upwards
     * inside the view — the view itself has to be translated. The target is the vertical
     * centre of the status bar (where the native icons sit), lifted a little further.
     */
    private fun alignToStatusBar() {
        if (!alignInStatusBar || aligned || width <= 0 || height <= 0) return
        val bar = statusBarHeight
        if (bar <= 0) return
        val scale = min(width, height) / 100f
        val contentHeight = 100f * scale
        val target = (bar - contentHeight) / 2f - EXTRA_SHIFT_DP * resources.displayMetrics.density
        val location = IntArray(2)
        getLocationOnScreen(location)
        val absoluteTop = location[1]
        // Only the views that really live in the status bar are moved; secondary displays
        // and the always-on screen keep their own position.
        if (absoluteTop > bar) {
            aligned = true
            return
        }
        translationY = target - absoluteTop
        aligned = true
        de.robv.android.xposed.XposedBridge.log(
            "3IN1: aligned shift=${translationY} top=$absoluteTop target=$target bar=$bar " +
                "w=$width h=$height scale=$scale")
    }

    var config = IndicatorConfig()
        set(value) { field = value; invalidate() }
    var state = IndicatorState.demo
        set(value) { field = value; contentDescription = value.description(); invalidate() }
    var tint = Color.BLACK
        set(value) { field = value; invalidate() }

    /**
     * Whether the view places itself inside the status bar.
     *
     * The preview inside the settings screen shows the indicator on its own and must not
     * be moved around.
     */
    var alignInStatusBar = true

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { alignToStatusBar() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        aligned = false
        post { alignToStatusBar() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!aligned) alignToStatusBar()
        renderer.draw(canvas, width.toFloat(), height.toFloat(), state, config, tint)
    }

    private companion object {
        /** Extra lift on top of the status bar centre, in device independent pixels. */
        const val EXTRA_SHIFT_DP = 3f
    }
}
