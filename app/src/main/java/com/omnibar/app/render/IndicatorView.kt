package com.omnibar.app.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import com.omnibar.app.config.IndicatorConfig
import com.omnibar.app.model.IndicatorState
import com.omnibar.app.model.WifiPhase

class IndicatorView(context: Context) : View(context) {
    private val renderer = IndicatorRenderer()
    private var aligned = false
    private var anchorListener: View.OnLayoutChangeListener? = null

    /**
     * The native icon row the indicator has to sit on the same line with.
     *
     * On this firmware the battery lives on the **left** of the status bar, next to the
     * clock, the alarm and the bluetooth icon. The container of those icons is the
     * reference: the indicator is centred on it, so it never drifts away from the row.
     */
    var alignmentAnchor: View? = null
        set(value) {
            if (field === value) return
            field?.let { old -> anchorListener?.let { old.removeOnLayoutChangeListener(it) } }
            anchorListener = null
            field = value
            value?.let { anchor ->
                val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    aligned = false
                    post { alignToStatusBar() }
                }
                anchorListener = listener
                anchor.addOnLayoutChangeListener(listener)
            }
            aligned = false
            post { alignToStatusBar() }
        }

    /**
     * Centres the indicator on the native icon row.
     *
     * The view is exactly as tall as its content and lives inside the battery view, which
     * sits lower than the other icons. The icon row next to it is the reference: the
     * indicator is centred on that row, so the percentage, the charging bolt and the signal
     * ring sit on one horizontal line with the clock, the alarm and the bluetooth icon.
     *
     * Nothing inside the indicator is moved here — the internal layout (Wi-Fi fan, network
     * label) is the renderer's business. Only the whole group is placed.
     */
    private fun alignToStatusBar() {
        if (!alignInStatusBar || aligned || width <= 0 || height <= 0) return
        val anchor = alignmentAnchor
        val location = IntArray(2)
        getLocationOnScreen(location)
        // getLocationOnScreen() already contains the current translation, so remove it to
        // get the laid-out position; the shift is applied to that.
        val layoutTop = location[1] - translationY
        var anchorTop = -1
        var anchorHeight = -1
        val target = if (anchor != null && anchor.height > 0) {
            val anchorLocation = IntArray(2)
            anchor.getLocationOnScreen(anchorLocation)
            anchorTop = anchorLocation[1]
            anchorHeight = anchor.height
            // The drawn content is centred in the view, so centring the views puts the
            // indicator on the same line as the native icons.
            anchorTop + anchorHeight / 2f - height / 2f
        } else {
            layoutTop
        }
        translationY = target - layoutTop
        aligned = true
        android.util.Log.d(
            "3IN1",
            "aligned shift=${translationY} top=$layoutTop target=$target " +
                "anchorTop=$anchorTop anchorH=$anchorHeight w=$width h=$height"
        )
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
        // While the Wi-Fi is connecting the bars climb, so the view has to ask for the
        // next frame on its own — the state itself does not change in between.
        val connecting = state.wifi == WifiPhase.CONNECTING
        val phase = if (connecting) {
            (SystemClock.uptimeMillis() % CONNECT_CYCLE_MS) / CONNECT_CYCLE_MS.toFloat()
        } else 0f
        renderer.draw(canvas, width.toFloat(), height.toFloat(), state, config, tint, 0f, phase)
        if (connecting) postInvalidateOnAnimation()
    }

    private companion object {
        /** Length of one connecting cycle; the three bars rise once per cycle. */
        const val CONNECT_CYCLE_MS = 900L
    }
}
