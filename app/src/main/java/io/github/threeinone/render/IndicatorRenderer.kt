package io.github.threeinone.render

import android.graphics.*
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import kotlin.math.min
import kotlin.math.sin

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
    /** Last logged column state; keeps the diagnostic line from repeating every frame. */
    private var lastColumnLog = ""

    /**
     * The layout of the indicator, in the 100-unit box the drawing is normalized to.
     *
     * The constants are public so that the tests can measure the marks against the
     * same numbers the renderer draws them with.
     */
    companion object {
        /** Radius of the signal-dot ring. */
        const val RING_RADIUS = 44f
        /** Vertical centre of that ring. */
        const val RING_CENTER_Y = 53f

        /** Signal dot radius. */
        const val SIGNAL_DOT_RADIUS = 5.4f

        /**
         * Centre of the Wi-Fi fan.
         *
         * The fan takes the place of the network label, so it has to sit exactly where the
         * label sits: the glyph runs from y = 35 (arc apex) to y = 69 (the dot), which
         * puts its middle on the same line as the "4G" / "5G" text (centre 54) and inside
         * the signal dot ring (centre 53). Below that the fan sagged towards the bottom,
         * above it it floated into the percentage.
         *
         * It sits a little lower than that midpoint, so the gap to the percentage above
         * it is wider than the one to the battery arc below it.
         */
        const val WIFI_CENTER_Y = 70f

        /** Radii of the Wi-Fi arcs (outermost first); two arcs plus the dot make three steps. */
        val WIFI_ARC_RADII = floatArrayOf(25f, 13f)

        /**
         * How far the number hangs below the font ascent.
         *
         * The Wi-Fi fan reaches up to y = 39, so the percentage has to sit above that:
         * with a small negative offset its glyphs end at y = 31 and the fan no longer
         * runs into the digits.
         */
        const val NUMBER_ASCENT_OFFSET = -5.5f

        /** The number may grow at most this tall (its top stays ~1.5 units below the edge). */
        const val NUMBER_MAX_ASCENT = 43.5f

        /** …and it may not sink past the Wi-Fi fan (whose apex is at y = 35). */
        const val NUMBER_MAX_BASELINE = 38f

        /** Widest the number may get before it would touch the signal dots. */
        const val NUMBER_MAX_WIDTH = 60f

        /** Alpha of an unlit signal dot — faint enough to read as an empty slot. */
        const val DOT_EMPTY_ALPHA = 38

        /**
         * Font size of the cellular label ("4G" / "5G") at 100 % text scale. The label
         * takes the place of the Wi-Fi fan, so it is sized to match that fan: at the
         * default text scale a bold two-character label is about as wide as the fan.
         */
        const val NETWORK_LABEL_BASE_SIZE = 26f

        /** Largest the label may become — beyond that it would reach the signal dots. */
        const val NETWORK_LABEL_MAX_SIZE = 60f

        /** Smallest the label may become. */
        const val NETWORK_LABEL_MIN_SIZE = 12f

        /**
         * Centre of the label band.
         *
         * The label takes the place of the Wi-Fi fan, so it is centred where the fan is:
         * in the middle of the signal dot ring instead of sagging towards the bottom.
         */
        const val NETWORK_LABEL_CENTER = 54f

        /**
         * Widest the label may get before it would touch the signal dots.
         *
         * The inner edge of the dot columns sits at x = 88 units, so the label may span
         * 72 units and still keep a visible gap.
         */
        const val NETWORK_LABEL_MAX_WIDTH = 72f

        /**
         * "SOS" is three letters, so it does not fit the band at the label size.
         */
        const val EMERGENCY_SIZE_RATIO = 0.74f

        /** Length of one bar of the "--" placeholder. */
        const val PLACEHOLDER_BAR_WIDTH = 13f

        /** Thickness of one bar. */
        const val PLACEHOLDER_BAR_HEIGHT = 5f

        /** Gap between the two bars. */
        const val PLACEHOLDER_BAR_GAP = 6f

        /**
         * The faint "sim" is a word rather than an abbreviation, so it is drawn small
         * enough to fit between the signal dots.
         */
        const val SIM_SIZE_RATIO = 0.62f

        /** How pale the "sim" word is drawn — it is a remark, not a headline. */
        const val SIM_DIM_ALPHA = 92

        /**
         * Where the Wi-Fi state marker hangs.
         *
         * "!" (no internet), "?" (needs a login) and "..." (connecting) used to be drawn
         * right on top of the fan: the mark sat on the end of the outer arc and the dots
         * of "..." lay across the inner one, so glyph and mark merged into one blob.
         *
         * They now hang in the top right corner of the fan's own box — the wedge between
         * the percentage above and the arc's shoulder below, where nothing else is drawn.
         */
        const val WIFI_MARKER_X = 72f

        /** Baseline of the marker; the outer arc only reaches up to y = 42. */
        const val WIFI_MARKER_BASELINE = 44f

        /** Size of the marker. */
        const val WIFI_MARKER_SIZE = 12f

        /**
         * Largest the charging bolt may get.
         *
         * The badge lives in the top corner of the indicator, so the corner itself is
         * what limits its size: the signal dot column below and the digits to the side.
         */
        const val BOLT_MAX_SCALE = 1.3f

        /** Width of the bolt glyph. */
        const val BOLT_WIDTH = 17f

        /** Height of the bolt glyph. */
        const val BOLT_HEIGHT = 21f

        /** Gap between the percentage and the badge next to it. */
        const val BOLT_GAP = 1.2f

        /** The badge hangs flush against this edge of the 100-unit box. */
        const val BOLT_EDGE_RIGHT = 99.5f

        /** …and against this one, when it hangs on the left of the digits. */
        const val BOLT_EDGE_LEFT = 0.5f

        /** Top edge of the badge — it sits on the top line of the indicator. */
        const val BOLT_TOP = 0f

        /** How far the badge rises above the top of the digits. */
        const val BOLT_RISE = 3f

        /** The badge may not reach the signal dots — it stays this far above them. */
        const val BOLT_DOT_CLEARANCE = 1.2f

        /**
         * Scale of the flight-mode plane.
         *
         * The plane takes the place of the Wi-Fi fan, but it used to be drawn at barely a
         * fifth of the glyph size and read as a speck next to the network label. At this
         * scale it is clearly taller than the "4G" / "5G" text — the eye compares the two,
         * because they occupy the same place in the layout.
         */
        const val AIRPLANE_SCALE = 0.40f

        /**
         * Top of the band the plane may use.
         *
         * The percentage hangs from the top edge of the indicator, so the plane has to
         * start below the digits; it never rises above this line either.
         */
        const val AIRPLANE_MIN_TOP = 36f

        /** Width of the VPN shield glyph in its own units. */
        const val VPN_WIDTH = 10f

        /** Height of the VPN shield glyph in its own units (top edge down to the tip). */
        const val VPN_HEIGHT = 12f

        /** Stroke of the shield, in indicator units — it adds a rim on every side. */
        const val VPN_STROKE = 2.4f

        /** Largest the shield may get — it stays a badge, not a second indicator. */
        const val VPN_MAX_SCALE = 1.6f

        /** Gap between the shield and the percentage. */
        const val VPN_GAP = 3f

        /** Clearance the shield keeps from the edge of the box. */
        const val VPN_EDGE = 1f

        /**
         * Top edge of the shield.
         *
         * The shield is a corner badge, exactly like the charging bolt: it hangs on the
         * top line of the indicator instead of floating next to the digits. That is what
         * keeps it clear of the signal dots — the left column starts below the digits,
         * and a badge that hangs from the top cannot reach it.
         */
        const val VPN_TOP = 0f

        /** How far the shield may rise above the top of the digits. */
        const val VPN_RISE = 3f

        /**
         * Clearance the shield keeps from the signal dots.
         *
         * The shield used to be pinned to the percentage only, so its pointed bottom
         * reached into the top left signal dot and the two marks ran together. The room
         * down to that dot now limits the size instead, which keeps the gap whatever the
         * dot spacing is.
         */
        const val VPN_DOT_CLEARANCE = 3f

        /** Right edge of the box, for the mirrored layout. */
        const val BOX_EDGE_RIGHT = 99f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val path = Path()

    /** Left edge of the percentage glyphs. */
    private var numberLeft = 0f

    /** Right edge of the percentage glyphs — the bolt is anchored to it. */
    private var numberRight = 0f

    /** Top edge of the percentage glyphs. */
    private var numberTop = 0f

    /** Bottom edge of the percentage glyphs — the badges below it start there. */
    private var numberBottom = 0f

    fun draw(
        canvas: Canvas, width: Float, height: Float, state: IndicatorState, config: IndicatorConfig,
        tint: Int, extraShiftPx: Float = 0f
    ) {
        if (width <= 0 || height <= 0) return
        val scale = min(width / 100f, height / 100f)
        val save = canvas.save()
        try {
            // The caller lifts the whole indicator inside the status bar: the Wi-Fi fan,
            // the network label, the battery number and the charging bolt would otherwise
            // drift towards the bottom of the bar.
            canvas.translate((width - 100 * scale) / 2, (height - 100 * scale) / 2 - extraShiftPx)
            canvas.scale(scale, scale)
            val battery = config.batteryColor(state, tint)

            val spread = config.dotSpacing / 100f
            val dotRadius = SIGNAL_DOT_RADIUS * config.dotScale / 100f
            val ringRadius = ringRadius(dotRadius, spread, config)

            drawNumber(canvas, state, config, tint)
            // Top edge of the highest signal dot: the corner badges have to stay above it.
            val dotTop = RING_CENTER_Y +
                ringRadius * sin(Math.toRadians(-33.0 * spread)).toFloat() - dotRadius
            if (state.batteryPhase == BatteryPhase.CHARGING || state.batteryPhase == BatteryPhase.FULL) {
                // The VPN shield takes the top right corner in the mirrored layout, so the
                // badge moves to the other side there.
                val onLeft = config.swap && config.showVpn && state.vpn
                drawBolt(canvas, battery, dotTop, onLeft)
            }
            if (config.showVpn && state.vpn) drawVpn(canvas, tint, config.swap, dotTop)

            if (state.airplane) {
                airplane(canvas, tint)
            } else if (state.wifiConnected || state.wifi == WifiPhase.CONNECTING) {
                wifiFan(canvas, config, tint, state)
            } else {
                drawNetworkFallback(canvas, state, config, tint)
            }

            drawSignalDots(canvas, state, config, tint, spread, dotRadius, ringRadius)
            drawBatteryArc(canvas, state, config, tint, battery, ringRadius)

            logSignalColumns(state, config)

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
        prepareText(value, size, config.batteryColor(state, tint), 1000f)
        // The glyph hangs from the top edge and grows downwards: the baseline follows the
        // real ascent, so every step of the size slider is visible instead of saturating.
        var ascent = -paint.fontMetrics.ascent
        if (ascent > NUMBER_MAX_ASCENT) {
            paint.textSize *= NUMBER_MAX_ASCENT / ascent
            ascent = -paint.fontMetrics.ascent
        }
        val baseline = (ascent + NUMBER_ASCENT_OFFSET).coerceAtMost(NUMBER_MAX_BASELINE)
        // A three-digit reading is too wide for the gap between the signal dots: squeeze it
        // horizontally instead of shrinking it, so the height still follows the slider.
        val width = paint.measureText(value)
        val squeeze = if (width > NUMBER_MAX_WIDTH) NUMBER_MAX_WIDTH / width else 1f
        if (squeeze < 1f) {
            val save = canvas.save()
            canvas.translate(50f, 0f)
            canvas.scale(squeeze, 1f)
            canvas.drawText(value, 0f, baseline, paint)
            canvas.restoreToCount(save)
        } else {
            canvas.drawText(value, 50f, baseline, paint)
        }
        // Remember where the ink ends: the charging bolt hangs on that corner as a badge.
        // getTextBounds() reports the box relative to the drawing origin, while the text is
        // centred on x = 50 — and squeezed about that point when it is too wide.
        val glyphs = Rect()
        paint.getTextBounds(value, 0, value.length, glyphs)
        val origin = 50f - width * squeeze / 2f
        numberLeft = origin + glyphs.left * squeeze
        numberRight = origin + glyphs.right * squeeze
        numberTop = baseline + glyphs.top
        numberBottom = baseline + glyphs.bottom
    }

    // ------------------------------------------------------------------ network label

    /**
     * Draws the cellular type ("4G" / "5G") in the band the Wi-Fi fan occupies.
     *
     * The label hangs from the same top edge as the Wi-Fi fan (y = 47) and grows
     * downwards, exactly like the battery percentage above it. That way a large text
     * scale can never run into the percentage, and the label still clears the battery
     * arc at the bottom — the width is clamped so it never reaches the signal dots.
     */
    private fun drawNetworkLabel(
        canvas: Canvas, value: String, config: IndicatorConfig, tint: Int, ratio: Float = 1f,
        alpha: Int = 255
    ) {
        val size = (NETWORK_LABEL_BASE_SIZE * config.textScale / 100f * ratio)
            .coerceIn(NETWORK_LABEL_MIN_SIZE, NETWORK_LABEL_MAX_SIZE)
        prepareText(value, size, withAlpha(tint, alpha), NETWORK_LABEL_MAX_WIDTH)
        val bounds = Rect()
        paint.getTextBounds(value, 0, value.length, bounds)
        // Centred in the band, so the label sits in the middle of the dot ring.
        val baseline = NETWORK_LABEL_CENTER - bounds.top - bounds.height() / 2f
        canvas.drawText(value, 50f, baseline, paint)
    }

    /**
     * The middle band when there is no Wi-Fi fan to show.
     *
     * Every problem used to collapse into one tiny "--", so a phone without a SIM, a
     * phone in flight mode and a phone without signal all looked the same — and the
     * dash pair was so small that it disappeared in the status bar. Each case gets a
     * mark of its own now:
     *  - a ready SIM shows the network type ("4G" / "5G"),
     *  - an emergency-only SIM shows "SOS", the mark Android itself uses,
     *  - a missing, disabled or locked SIM shows a faint "sim",
     *  - no signal at all shows the "--" placeholder.
     */
    private fun drawNetworkFallback(
        canvas: Canvas, state: IndicatorState, config: IndicatorConfig, tint: Int
    ) {
        when (state.sim) {
            SimPhase.READY -> {
                val type = state.networkType
                if (type.isBlank()) drawPlaceholder(canvas, tint)
                else drawNetworkLabel(canvas, type, config, tint)
            }
            SimPhase.EMERGENCY -> drawNetworkLabel(canvas, "SOS", config, tint, EMERGENCY_SIZE_RATIO)
            SimPhase.ABSENT, SimPhase.DISABLED, SimPhase.LOCKED ->
                drawNetworkLabel(canvas, "sim", config, tint, SIM_SIZE_RATIO, SIM_DIM_ALPHA)
            else -> drawPlaceholder(canvas, tint)
        }
    }

    /**
     * The "--" placeholder for a phone without signal.
     *
     * Set as a text glyph it was a hairline: a dash pair carries almost no ink, so it
     * vanished in the status bar. Drawn as two bars it finally has the presence of a
     * real network type, and it keeps the meaning of the placeholder it replaces.
     */
    private fun drawPlaceholder(canvas: Canvas, tint: Int) {
        fill(tint)
        val y = NETWORK_LABEL_CENTER
        val half = PLACEHOLDER_BAR_HEIGHT / 2f
        val left = 50f - PLACEHOLDER_BAR_GAP / 2f
        val right = 50f + PLACEHOLDER_BAR_GAP / 2f
        canvas.drawRoundRect(left - PLACEHOLDER_BAR_WIDTH, y - half, left, y + half,
            half, half, paint)
        canvas.drawRoundRect(right, y - half, right + PLACEHOLDER_BAR_WIDTH, y + half,
            half, half, paint)
    }

    // ------------------------------------------------------------------ signal dots

    /**
     * Eight dots on a ring — four per side — lit from the top down. With dual SIM the
     * secondary card uses a smaller inner ring so both cards stay distinguishable.
     */
    /**
     * Signal dots on a ring — four per side. With dual SIM the **left** half shows the
     * main card and the **right** half the secondary card; otherwise both halves mirror
     * the default card. Dots light up from the top down.
     */
    private fun drawSignalDots(
        canvas: Canvas, state: IndicatorState, config: IndicatorConfig, tint: Int,
        spread: Float, dotRadius: Float, ringRadius: Float
    ) {
        if (state.airplane) return
        val angles = floatArrayOf(-33f * spread, -11f * spread, 11f * spread, 33f * spread)

        val bySlot = state.sims.associateBy { it.slot }
        val card1 = bySlot[0]
        val card2 = bySlot[1]

        // Dual-card view: both columns are shown. Single-card view: only the card in use
        // is shown, and it keeps the column of its own slot.
        val (leftLevel, rightLevel) = columnLevels(state, config)
        val leftColor = if (state.roaming || (card1?.roaming == true)) config.roamingColor else tint
        val rightColor = if (card2?.roaming == true) config.roamingColor else tint
        drawDotSide(canvas, angles, -1f, ringRadius, dotRadius, leftLevel, leftColor)
        drawDotSide(canvas, angles, 1f, ringRadius, dotRadius, rightLevel, rightColor)
    }

    /**
     * Signal levels per column. The columns are bound to the SIM slots, not to whichever
     * card happens to be in use: card 1 (slot 0) always owns the left half, card 2 (slot 1)
     * the right one. A card that is switched off simply leaves its column empty.
     */
    private fun columnLevels(state: IndicatorState, config: IndicatorConfig): Pair<Int, Int> {
        val bySlot = state.sims.associateBy { it.slot }
        val card1 = bySlot[0]
        val card2 = bySlot[1]
        // The card that is currently used for data (falls back to card 1).
        val activeSlot = state.sims.firstOrNull { it.dataDefault }?.slot
            ?: card1?.slot ?: card2?.slot

        fun level(card: IndicatorSim?): Int = when {
            card == null -> 0
            card.phase != SimPhase.READY -> 0
            else -> card.level.coerceIn(0, 4)
        }

        val leftLevel = if (config.dualSim) level(card1) else if (activeSlot == 0) level(card1) else 0
        val rightLevel = if (config.dualSim) level(card2) else if (activeSlot == 1) level(card2) else 0
        return leftLevel to rightLevel
    }

    /**
     * Mirrors the column assignment into the log. It makes the secondary column verifiable
     * even when the status bar cannot be captured (locked screen, ambient display).
     */
    private fun logSignalColumns(state: IndicatorState, config: IndicatorConfig) {
        val (left, right) = columnLevels(state, config)
        val activeSlot = state.sims.firstOrNull { it.dataDefault }?.slot
        val line = "3IN1: columns dualSim=${config.dualSim} left=$left right=$right active=$activeSlot"
        if (line != lastColumnLog) {
            lastColumnLog = line
            android.util.Log.i("3IN1", line)
        }
    }

    /** One half of the ring; [dir] is -1 for the left side and +1 for the right. */
    private fun drawDotSide(
        canvas: Canvas, angles: FloatArray, dir: Float, radius: Float, dotRadius: Float,
        level: Int, color: Int
    ) {
        for (i in angles.indices) {
            val rad = Math.toRadians(angles[i].toDouble())
            val x = 50f + dir * radius * Math.cos(rad).toFloat()
            val y = RING_CENTER_Y + radius * Math.sin(rad).toFloat()
            // Unlit bars are drawn in a faint tint so they read as empty slots on both
            // light and dark status bars.
            if (i < level) fill(color) else fill(color, DOT_EMPTY_ALPHA)
            canvas.drawCircle(x, y, dotRadius, paint)
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
        // Centred inside the dot ring: the fan's mass sits around y = 50..65, not up top.
        canvas.scale(ws, ws, 50f, WIFI_CENTER_Y)
        // Three visible steps: the dot is the glyph's base and each extra arc is one more
        // bar, so the levels are easy to tell apart.
        val bars = (state.wifiLevel - 1).coerceIn(0, WIFI_ARC_RADII.size)
        for (i in WIFI_ARC_RADII.indices) {
            val radius = WIFI_ARC_RADII[i]
            bounds.set(50 - radius, WIFI_CENTER_Y - radius, 50 + radius, WIFI_CENTER_Y + radius)
            val active = state.wifi != WifiPhase.CONNECTING && bars > (WIFI_ARC_RADII.size - 1 - i)
            stroke(tint, 5.5f * config.stroke / 100f * .72f, if (active) 255 else 55)
            canvas.drawArc(bounds, 210f, 120f, false, paint)
        }
        fill(tint, if (state.wifiLevel >= 1 && state.wifi != WifiPhase.CONNECTING) 255 else 55)
        canvas.drawCircle(50f, WIFI_CENTER_Y, 3.4f, paint)
        canvas.restoreToCount(wSave)
        // The markers belong to the fan, but they must not touch it: they hang in the
        // top right corner of the fan's box, where the percentage leaves room.
        val marker = when (state.wifi) {
            WifiPhase.NO_INTERNET -> "!"
            WifiPhase.CAPTIVE -> "?"
            WifiPhase.CONNECTING -> "..."
            else -> null
        }
        if (marker != null) {
            text(canvas, marker, WIFI_MARKER_X, WIFI_MARKER_BASELINE, WIFI_MARKER_SIZE, tint)
        }
    }

    // ------------------------------------------------------------------ badges

    /**
     * The charging bolt, drawn as a badge in the top corner of the indicator.
     *
     * Parked in the middle of the number it used to be swallowed by the digits — while
     * charging both are painted in the same charging colour, so an overlap smears them
     * into one blob. As a badge it stands next to the percentage instead: it touches
     * neither the digits nor the signal dots.
     *
     * The badge is pinned to the corner, so it keeps its place while the percentage
     * changes width. It shrinks only when a wide reading such as "100" would push it
     * into the digits.
     */
    private fun drawBolt(canvas: Canvas, color: Int, dotTop: Float, onLeft: Boolean) {
        val save = canvas.save()
        try {
            // The badge hangs from the top line of the indicator.
            val top = maxOf(BOLT_TOP, numberTop - BOLT_RISE)
            // Vertical room: down to the top of the first signal dot.
            val heightRoom = dotTop - BOLT_DOT_CLEARANCE - top
            // Horizontal room: between the digits and the corner the badge is pinned to.
            val widthRoom = if (onLeft) numberLeft - BOLT_GAP - BOLT_EDGE_LEFT
                            else BOLT_EDGE_RIGHT - numberRight - BOLT_GAP
            var s = min(BOLT_MAX_SCALE, min(heightRoom / BOLT_HEIGHT, widthRoom / BOLT_WIDTH))
            s = s.coerceAtLeast(0.25f)
            val badgeWidth = BOLT_WIDTH * s
            if (onLeft) {
                // On the left the badge is mirrored, so its tip still points outwards.
                canvas.translate(BOLT_EDGE_LEFT + badgeWidth, top)
                canvas.scale(-s, s)
            } else {
                canvas.translate(BOLT_EDGE_RIGHT - badgeWidth, top)
                canvas.scale(s, s)
            }
            path.reset()
            // A beefier bolt than the original glyph: it has to read as a charging mark
            // at status bar size.
            path.moveTo(13f, 0f); path.lineTo(0f, 13f); path.lineTo(7f, 13f)
            path.lineTo(4f, 21f); path.lineTo(17f, 8f); path.lineTo(10f, 8f); path.close()
            fill(color); canvas.drawPath(path, paint)
        } finally { canvas.restoreToCount(save) }
    }

    /**
     * The VPN shield.
     *
     * It used to be a 10 × 12 unit speck in the top corner — barely visible at status bar
     * size. It now grows into the space next to the percentage and keeps the same stroke
     * width, so a larger shield simply carries more detail instead of turning into a blob.
     *
     * The shield stands next to the percentage: on the left in the normal layout, on the
     * right in the mirrored one, where the charging bolt takes the opposite corner.
     *
     * It hangs from the **top line** of the indicator like the charging bolt, and it is
     * sized by the room down to the signal dots. Pinned to the percentage alone it used
     * to hang low enough for its pointed bottom to run into the top left signal dot, and
     * the two marks merged into one blob.
     */
    private fun drawVpn(canvas: Canvas, color: Int, swap: Boolean, dotTop: Float) {
        val save = canvas.save()
        try {
            val anchor = if (swap) numberRight + VPN_GAP else numberLeft - VPN_GAP
            val edge = if (swap) BOX_EDGE_RIGHT - VPN_EDGE else VPN_EDGE
            val room = if (swap) edge - anchor else anchor - edge
            // The ink of the shield — not its path — is what has to stay clear of the
            // dots, and the stroke adds a rim on every side.
            val inkTop = maxOf(VPN_TOP, numberTop - VPN_RISE)
            val heightRoom = dotTop - VPN_DOT_CLEARANCE - inkTop - VPN_STROKE
            val scale = min(VPN_MAX_SCALE, min(heightRoom / VPN_HEIGHT, room / VPN_WIDTH))
                .coerceAtLeast(0.2f)
            val width = VPN_WIDTH * scale
            // The path starts half a stroke below the top of the ink.
            canvas.translate(if (swap) anchor else anchor - width, inkTop + VPN_STROKE / 2f)
            canvas.scale(scale, scale)
            stroke(color, VPN_STROKE / scale)
            path.reset()
            path.moveTo(0f, 0f); path.lineTo(10f, 0f); path.lineTo(10f, 6f)
            path.lineTo(5f, 12f); path.lineTo(0f, 6f); path.close()
            canvas.drawPath(path, paint)
            path.reset()
            path.moveTo(3f, 5f); path.lineTo(5f, 7f); path.lineTo(8f, 3f)
            canvas.drawPath(path, paint)
        } finally { canvas.restoreToCount(save) }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The flight-mode plane.
     *
     * It takes the place of the Wi-Fi fan, so it sits in the middle of the indicator. It
     * used to be scaled down to a fifth of the glyph size, which made it a speck next to
     * the network label; it now fills the band between the percentage and the battery arc
     * and is taller than the "4G" / "5G" text it replaces.
     */
    private fun airplane(c: Canvas, color: Int) {
        val save = c.save()
        try {
            val scale = AIRPLANE_SCALE
            val width = 100f * scale
            // The percentage hangs from the top edge, so the plane starts below the
            // digits and stays centred on the indicator.
            val top = maxOf(AIRPLANE_MIN_TOP, numberBottom + 2f)
            c.translate(50f - width / 2f, top)
            c.scale(scale, scale)
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
