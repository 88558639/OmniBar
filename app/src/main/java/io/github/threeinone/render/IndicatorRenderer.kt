package io.github.threeinone.render

import android.graphics.*
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import kotlin.math.min

/**
 * A normalized 100-unit drawing shared by the real status bar and preview.
 *
 * Layout follows the SuperIcon pill:
 *  - the battery percentage is a large number at the top,
 *  - the Wi-Fi fan sits in the middle (the network type is shown there instead when
 *    Wi-Fi is off),
 *  - the cellular signal is a ring of eight equally sized dots around the fan — four on
 *    each side — lit progressively from the top down,
 *  - the battery level is an arc across the bottom of the same ring.
 */
class IndicatorRenderer {
    private companion object {
        /** Radius of the signal-dot ring. */
        const val RING_RADIUS = 44f

        /** Vertical centre of that ring. */
        const val RING_CENTER_Y = 53f

        /** Signal dot radius. */
        const val SIGNAL_DOT_RADIUS = 5.4f

        /** Secondary (second SIM) ring radius and dot radius. */
        const val SUB_RING_RADIUS = 33f
        const val SUB_DOT_RADIUS = 4.1f

        /** Baseline of the battery percentage number. */
        const val NUMBER_BASELINE = 28f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val path = Path()

    fun draw(canvas: Canvas, width: Float, height: Float, state: IndicatorState, config: IndicatorConfig, tint: Int) {
        if (width <= 0 || height <= 0) return
        val scale = min(width / 100f, height / 100f)
        val save = canvas.save()
        try {
            canvas.translate((width - 100 * scale) / 2, (height - 100 * scale) / 2)
            canvas.scale(scale, scale)
            val battery = config.batteryColor(state, tint)

            drawNumber(canvas, state, config, tint)
            if (state.batteryPhase == BatteryPhase.CHARGING || state.batteryPhase == BatteryPhase.FULL) {
                drawBolt(canvas, battery)
            }
            if (config.showVpn && state.vpn) drawVpn(canvas, tint, config.swap)

            if (state.airplane) {
                airplane(canvas, tint)
            } else if (state.wifiConnected || state.wifi == WifiPhase.CONNECTING) {
                wifiFan(canvas, config, tint, state)
            } else if (state.sim == SimPhase.READY) {
                text(canvas, state.networkType.ifBlank { "--" }, 50f, 57f,
                    14f * config.textScale / 100f, tint, 60f, centerVertically = true)
            } else {
                text(canvas, "--", 50f, 57f, 17f, tint)
            }

            val spread = config.dotSpacing / 100f
            val dotRadius = SIGNAL_DOT_RADIUS * config.dotScale / 100f
            val ringRadius = ringRadius(dotRadius, spread, config)

            drawSignalDots(canvas, state, config, tint, spread, dotRadius, ringRadius)
            drawBatteryArc(canvas, state, config, tint, battery, ringRadius)

            if (config.showActivity) {
                val act = if (state.wifiConnected || state.wifi == WifiPhase.CONNECTING) {
                    state.wifiActivity
                } else state.mobileActivity
                drawActivityArrows(canvas, act, tint)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    // ------------------------------------------------------------------ battery number

    private fun drawNumber(canvas: Canvas, state: IndicatorState, config: IndicatorConfig, tint: Int) {
        val value = if (state.battery < 0) "?" else state.battery.coerceIn(0, 100).toString()
        val size = 34f * config.numberScale / 100f
        prepareText(value, size, config.batteryColor(state, tint), 60f)
        // The width cap alone is not enough: a tall glyph would be cut off at the top of
        // the canvas, so clamp the size by the real ascent as well.
        val ascent = -paint.fontMetrics.ascent
        if (ascent > 0f) {
            val headroom = NUMBER_BASELINE - 0.5f
            if (ascent > headroom) paint.textSize *= headroom / ascent
        }
        canvas.drawText(value, 50f, NUMBER_BASELINE, paint)
    }

    // ------------------------------------------------------------------ signal dots

    /**
     * Eight dots on a ring — four per side — lit from the top down. With dual SIM the
     * secondary card uses a smaller inner ring so both cards stay distinguishable.
     */
    private fun drawSignalDots(
        canvas: Canvas, state: IndicatorState, config: IndicatorConfig, tint: Int,
        spread: Float, dotRadius: Float, ringRadius: Float
    ) {
        if (state.airplane) return
        val angles = floatArrayOf(-33f * spread, -11f * spread, 11f * spread, 33f * spread)

        val primary = state.sims.firstOrNull { it.dataDefault } ?: state.sims.firstOrNull()
        val primaryLevel = if (state.sim == SimPhase.READY) state.mobileLevel.coerceIn(0, 4) else 0
        val primaryRoaming = state.roaming || (primary?.roaming == true)
        drawDotRing(canvas, angles, ringRadius, dotRadius,
            primaryLevel, if (primaryRoaming) config.roamingColor else tint)

        if (config.dualSim) {
            val secondary = state.sims.firstOrNull { !it.dataDefault } ?: state.sims.getOrNull(1)
            if (secondary != null) {
                val level = if (secondary.phase == SimPhase.READY) secondary.level.coerceIn(0, 4) else 0
                val subDot = SUB_DOT_RADIUS * config.dotScale / 100f
                drawDotRing(canvas, angles, SUB_RING_RADIUS, subDot,
                    level, if (secondary.roaming) config.roamingColor else tint)
            }
        }
    }

    /**
     * Keep the dot ring inside the 100-unit canvas no matter how large the dots or the
     * angular spread get — the indicator is clipped by the status bar otherwise.
     */
    private fun ringRadius(dotRadius: Float, spread: Float, config: IndicatorConfig): Float {
        val minAngle = Math.toRadians(11.0 * spread)
        val maxAngle = Math.toRadians(33.0 * spread)
        val strokeHalf = 5.5f * config.stroke / 200f
        val byWidth = (49.5f - dotRadius) / Math.cos(minAngle).toFloat()
        val byHeight = (49.5f - maxOf(dotRadius, strokeHalf)) / Math.sin(maxAngle).toFloat()
        return min(RING_RADIUS, min(byWidth, byHeight))
    }

    private fun drawDotRing(
        canvas: Canvas, angles: FloatArray, radius: Float, dotRadius: Float,
        level: Int, color: Int
    ) {
        for (side in 0..1) {
            // Mirror the horizontal offset for the left side (cos is even, so negating the
            // angle would put both halves on the same side).
            val dir = if (side == 0) 1f else -1f
            for (i in angles.indices) {
                val rad = Math.toRadians(angles[i].toDouble())
                val x = 50f + dir * radius * Math.cos(rad).toFloat()
                val y = RING_CENTER_Y + radius * Math.sin(rad).toFloat()
                fill(color, if (i < level) 255 else 55)
                canvas.drawCircle(x, y, dotRadius, paint)
            }
        }
    }

    // ------------------------------------------------------------------ battery arc

    private fun drawBatteryArc(
        canvas: Canvas, state: IndicatorState, config: IndicatorConfig, tint: Int, battery: Int,
        ringRadius: Float
    ) {
        bounds.set(50f - ringRadius, RING_CENTER_Y - ringRadius,
            50f + ringRadius, RING_CENTER_Y + ringRadius)
        val line = 5.5f * config.stroke / 100f
        // Track across the bottom of the ring.
        stroke(tint, line, 46)
        canvas.drawArc(bounds, 135f, -90f, false, paint)
        val progress = state.progress
        if (progress > 0) {
            stroke(battery, line)
            canvas.drawArc(bounds, 135f, -90f * progress, false, paint)
        }
    }

    // ------------------------------------------------------------------ wifi

    private fun wifiFan(canvas: Canvas, config: IndicatorConfig, tint: Int, state: IndicatorState) {
        val ws = min(config.wifiScale / 115f, 1.16f)
        val wSave = canvas.save()
        canvas.scale(ws, ws, 50f, 56f)
        for (i in 0..2) {
            val radius = 28f - i * 9f
            bounds.set(50 - radius, 56 - radius, 50 + radius, 56 + radius)
            val active = state.wifi != WifiPhase.CONNECTING && state.wifiLevel >= 4 - i
            stroke(tint, 5.5f * config.stroke / 100f * .72f, if (active) 255 else 55)
            canvas.drawArc(bounds, 225f, 90f, false, paint)
        }
        fill(tint, if (state.wifiLevel >= 1 && state.wifi != WifiPhase.CONNECTING) 255 else 55)
        canvas.drawCircle(50f, 56f, 3.1f, paint)
        canvas.restoreToCount(wSave)
        if (state.wifi in setOf(WifiPhase.NO_INTERNET, WifiPhase.CAPTIVE)) {
            text(canvas, if (state.wifi == WifiPhase.CAPTIVE) "?" else "!", 74f, 56f, 14f, tint)
        } else if (state.wifi == WifiPhase.CONNECTING) {
            text(canvas, "...", 50f, 60f, 12f, tint)
        }
    }

    // ------------------------------------------------------------------ badges

    private fun drawBolt(canvas: Canvas, color: Int) {
        val save = canvas.save()
        try {
            canvas.translate(68f, 1f)
            canvas.scale(.42f, .42f)
            path.reset()
            path.moveTo(53f, 0f); path.lineTo(44f, 12f); path.lineTo(50f, 12f)
            path.lineTo(47f, 21f); path.lineTo(58f, 8f); path.lineTo(52f, 8f); path.close()
            fill(color); canvas.drawPath(path, paint)
        } finally { canvas.restoreToCount(save) }
    }

    private fun drawVpn(canvas: Canvas, color: Int, swap: Boolean) {
        val save = canvas.save()
        try {
            val x = if (swap) 78f else 12f
            stroke(color, 2.4f)
            path.reset()
            path.moveTo(x, 4f); path.lineTo(x + 10f, 4f); path.lineTo(x + 10f, 10f)
            path.lineTo(x + 5f, 16f); path.lineTo(x, 10f); path.close()
            canvas.drawPath(path, paint)
            path.reset()
            path.moveTo(x + 3f, 9f); path.lineTo(x + 5f, 11f); path.lineTo(x + 8f, 7f)
            canvas.drawPath(path, paint)
        } finally { canvas.restoreToCount(save) }
    }

    // ------------------------------------------------------------------ helpers

    private fun airplane(c: Canvas, color: Int) {
        val save = c.save()
        try {
            c.translate(38.5f, 41.5f)
            c.scale(.23f, .23f)
            path.reset()
            path.moveTo(50f, 0f)
            path.cubicTo(42f, 0f, 40f, 10f, 40f, 20f)
            path.lineTo(40f, 38f); path.lineTo(4f, 65f)
            path.quadTo(0f, 68f, 0f, 75f)
            path.quadTo(0f, 78f, 4f, 77f)
            path.lineTo(40f, 67f); path.lineTo(40f, 88f)
            path.lineTo(31f, 100f); path.quadTo(29f, 103f, 31f, 108f)
            path.lineTo(50f, 100f); path.lineTo(69f, 108f)
            path.quadTo(71f, 103f, 69f, 100f); path.lineTo(60f, 88f)
            path.lineTo(60f, 67f); path.lineTo(96f, 77f)
            path.quadTo(100f, 78f, 100f, 75f)
            path.quadTo(100f, 68f, 96f, 65f); path.lineTo(60f, 38f)
            path.lineTo(60f, 20f); path.cubicTo(60f, 10f, 58f, 0f, 50f, 0f)
            path.close()
            fill(color); c.drawPath(path, paint)
        } finally { c.restoreToCount(save) }
    }

    /** Up/down traffic arrows: down = receiving (IN), up = transmitting (OUT). */
    private fun drawActivityArrows(canvas: Canvas, act: DataActivity, tint: Int) {
        stroke(tint, 2.2f, if (act.hasIn) 255 else 70)
        path.reset()
        path.moveTo(38f, 73f); path.lineTo(42f, 78f); path.lineTo(46f, 73f)
        canvas.drawPath(path, paint)
        stroke(tint, 2.2f, if (act.hasOut) 255 else 70)
        path.reset()
        path.moveTo(54f, 79f); path.lineTo(58f, 74f); path.lineTo(62f, 79f)
        canvas.drawPath(path, paint)
    }

    private fun stroke(color: Int, width: Float, alpha: Int = 255) {
        paint.reset(); paint.isAntiAlias = true; paint.color = withAlpha(color, alpha)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int, alpha: Int = 255) {
        paint.reset(); paint.isAntiAlias = true; paint.color = withAlpha(color, alpha)
        paint.style = Paint.Style.FILL
    }

    private fun text(c: Canvas, value: String, x: Float, baseline: Float, size: Float, color: Int,
        maxWidth: Float = 100f, centerVertically: Boolean = false) {
        prepareText(value, size, color, maxWidth)
        val y = if (centerVertically) {
            val glyphBounds = Rect()
            paint.getTextBounds(value, 0, value.length, glyphBounds)
            baseline - (glyphBounds.top + glyphBounds.bottom) / 2f
        } else baseline
        c.drawText(value, x, y, paint)
    }

    private fun prepareText(value: String, size: Float, color: Int, maxWidth: Float) {
        fill(color); paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = size; paint.textAlign = Paint.Align.CENTER
        val measured = paint.measureText(value)
        if (measured > maxWidth) paint.textSize *= maxWidth / measured
    }

    /** Preserve the SystemUI tint alpha while still applying each drawing layer's local alpha. */
    private fun withAlpha(color: Int, localAlpha: Int): Int {
        val alpha = (Color.alpha(color) * localAlpha.coerceIn(0, 255) + 127) / 255
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
