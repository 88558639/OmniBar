package io.github.threeinone.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.IndicatorState
import kotlin.math.min

class IndicatorView(context: Context) : View(context) {
    private val renderer = IndicatorRenderer()

    /**
     * Lift of the whole indicator inside the status bar, in pixels.
     *
     * The canvas only reserves a square of `size` dp, so the remaining space of the status
     * bar has to be balanced by hand: without this the Wi-Fi fan, the network label and the
     * charging bolt sit visibly low. The first term is a fixed share of the canvas, the
     * second one an explicit dp offset that can be tuned against the real status bar.
     */
    private fun shiftPx(): Float {
        val scale = min(width, height) / 100f
        return CANVAS_SHIFT_UNITS * scale + EXTRA_SHIFT_DP * resources.displayMetrics.density
    }

    var config = IndicatorConfig()
        set(value) { field = value; invalidate() }
    var state = IndicatorState.demo
        set(value) { field = value; contentDescription = value.description(); invalidate() }
    var tint = Color.BLACK
        set(value) { field = value; invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, width.toFloat(), height.toFloat(), state, config, tint, shiftPx())
    }

    private companion object {
        /** Lift as a share of the 100-unit canvas. */
        const val CANVAS_SHIFT_UNITS = 8f

        /** Additional lift in device independent pixels. */
        const val EXTRA_SHIFT_DP = 3f
    }
}
