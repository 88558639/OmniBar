package com.omnibar.app

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.omnibar.app.config.IndicatorConfig
import com.omnibar.app.model.*
import com.omnibar.app.render.IndicatorRenderer
import com.omnibar.app.ui.CardSwitch
import com.omnibar.app.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderingTest {
    @Test fun allStatesRenderOnLightAndDarkWithoutOverflow() {
        val states = IndicatorState.previews
        val sheet = Bitmap.createBitmap(1000, states.size * 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        val renderer = IndicatorRenderer()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; color = Color.BLACK }
        states.forEachIndexed { index, (name, state) ->
            val y = index * 120f
            p.color = Color.WHITE; c.drawRect(0f, y, 500f, y + 120, p)
            p.color = Color.rgb(21, 25, 27); c.drawRect(500f, y, 1000f, y + 120, p)
            p.color = Color.BLACK; c.drawText(name, 12f, y + 28, p)
            for (mode in 0..1) {
                val tint = if (mode == 0) Color.BLACK else Color.WHITE
                for ((position, size) in listOf(22, 66, 100).withIndex()) {
                    val save = c.save()
                    c.translate(mode * 500 + 220f + position * 80, y + (120 - size) / 2)
                    renderer.draw(c, size.toFloat(), size.toFloat(), state, IndicatorConfig(), tint)
                    c.restoreToCount(save)
                }
            }
        }
        save(sheet, "states.png")
        // The indicator is drawn on a 100x100 unit canvas; 1 unit = 3 px here.
        val extreme = IndicatorConfig(stroke = 160, wifiScale = 150,
            dotScale = 140, dotSpacing = 125, textScale = 170)
        for ((_, state) in states) {
            val small = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
            renderer.draw(Canvas(small), 80f, 80f, state, extreme, Color.BLACK)
            val pixels = IntArray(80 * 80); small.getPixels(pixels, 0, 80, 0, 0, 80, 80)
            assertTrue("Blank state: $state", pixels.count { Color.alpha(it) > 0 } > 100)

            // The signal-dot ring is clamped to 49.5 units, so the outermost
            // pixel columns stay empty even with the most extreme settings.
            val big = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            renderer.draw(Canvas(big), 300f, 300f, state, extreme, Color.BLACK)
            for (y in 0 until 300) {
                assertEquals("Left overflow", 0, Color.alpha(big.getPixel(0, y)))
                assertEquals("Right overflow", 0, Color.alpha(big.getPixel(299, y)))
            }
            // Nothing may leave the 100-unit box sideways, and the number's
            // ascender as well as the battery arc may only graze the top and
            // the bottom edge by at most one unit.
            val spill = spillOutsideBox(state, extreme)
            assertTrue("Horizontal spill: $spill", spill[0] <= 0 && spill[2] <= 0)
            assertTrue("Vertical spill: $spill", spill[1] <= 3 && spill[3] <= 3)
        }
    }

    @Test fun batteryArcIsContinuousAcrossTheBottomWhenFull() {
        val full = render(IndicatorState.demo.copy(battery = 100), IndicatorConfig(colors = false))
        // The arc spans 135° (lower left) to 45° (lower right) around the ring centre (50, 53).
        for (deg in 135 downTo 45 step 5) {
            val (x, y) = arcPoint(deg)
            assertEquals("Gap in the battery arc at $deg°", 255, Color.alpha(full.getPixel(x, y)))
        }
        // Half a battery only fills the left half of that sweep, the rest is the faint track.
        val half = render(IndicatorState.demo.copy(battery = 50), IndicatorConfig(colors = false))
        val (leftX, leftY) = arcPoint(135)
        val (midX, midY) = arcPoint(90)
        val (rightX, rightY) = arcPoint(45)
        assertEquals(255, Color.alpha(half.getPixel(leftX, leftY)))
        assertEquals(255, Color.alpha(half.getPixel(midX, midY)))
        assertEquals(46, Color.alpha(half.getPixel(rightX, rightY)))
    }

    @Test fun chargingBoltAppearsOnlyWhileChargingOrFull() {
        // The bolt is a badge in the top corner of the indicator: it is pinned to the
        // right edge, above the signal dots.
        fun boltInk(state: IndicatorState): Int {
            val b = render(state)
            return (0 until 70).sumOf { y -> (275..299).count { x -> Color.alpha(b.getPixel(x, y)) > 0 } }
        }
        assertEquals("No bolt while discharging", 0, boltInk(IndicatorState.demo))
        assertTrue("Bolt missing while charging",
            boltInk(IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING)) > 50)
        assertTrue("Bolt missing when full",
            boltInk(IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL)) > 50)

        // The bolt uses the colour of the detected protocol and never erases the host
        // background. No protocol could be read here, so it is the unknown-protocol one.
        val cfg = IndicatorConfig()
        val composed = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(composed)
        c.drawColor(Color.rgb(21, 25, 27))
        IndicatorRenderer().draw(c, 300f, 300f,
            IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING), cfg, Color.BLACK)
        assertEquals("Background must not be erased", Color.rgb(21, 25, 27), composed.getPixel(15, 15))
        val solidBolt = (0 until 70).any { y ->
            (240..295).any { x -> composed.getPixel(x, y) == cfg.unknownColor }
        }
        assertTrue("The bolt must use the charging colour", solidBolt)
    }

    @Test fun wifiHasThreeSignalSteps() {
        // The fan sits at (50, 70) — centred where the network label is: a centre dot
        // plus two arcs whose apexes are at y = 57 (r = 13) and y = 45 (r = 25).
        val probes = listOf(210 to 70, 171 to 57, 135 to 45)
        // Three steps: dot only, dot + inner arc, dot + both arcs.
        for (level in 0..4) {
            val b = render(IndicatorState.demo.copy(wifiLevel = level), IndicatorConfig(wifiScale = 115))
            val litArcs = when (level) {
                0, 1 -> 0
                2 -> 1
                else -> 2
            }
            val expected = listOf(
                if (level >= 1) 255 else 55,
                if (litArcs >= 1) 255 else 55,
                if (litArcs >= 2) 255 else 55
            )
            probes.forEachIndexed { i, (y, unit) ->
                assertEquals("Wi-Fi step $i (y=$unit) at level $level", expected[i],
                    Color.alpha(b.getPixel(150, y)))
            }
        }
    }

    @Test fun networkTypeTextIsShownOnlyWhenSimIsReady() {
        val ready4g = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = "4G"))
        val ready5g = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = "5G"))
        assertFalse("The network type must be rendered when the SIM is ready", ready4g.sameAs(ready5g))
        assertTrue("No network type glyphs", ink(ready4g, 110, 190, 130, 190) > 200)

        val noSim4g = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF,
            sim = SimPhase.NO_SIGNAL, networkType = "4G"))
        val noSim5g = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF,
            sim = SimPhase.NO_SIGNAL, networkType = "5G"))
        assertTrue("No network type without a ready SIM", noSim4g.sameAs(noSim5g))

        // The real label fills the band; the "--" placeholder is a small dash pair in
        // the middle of it. No digit-shaped glyph may survive.
        val labelRows = inkRows(ready4g, 110, 190, 120, 230)
        val dashRows = inkRows(noSim4g, 110, 190, 120, 230)
        assertTrue("Placeholder missing", dashRows.isNotEmpty())
        assertTrue("The placeholder must be smaller than the label",
            dashRows.size < labelRows.size)
        assertTrue("The placeholder must sit inside the label band",
            dashRows.first() >= labelRows.first() && dashRows.last() <= labelRows.last())
    }

    @Test fun networkLabelIsCentredInTheFanBand() {
        for (label in listOf("2G", "3G", "4G", "5G")) {
            val b = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = label))
            // Scan only the label's own column band: the percentage number above and the signal
            // dots at the sides must not influence the measurement. The window starts
            // below the percentage (which ends at y = 38 units).
            val rows = inkRows(b, 100, 200, 120, 230)
            assertTrue("No glyph for $label", rows.isNotEmpty())
            // The label is centred in the dot ring (ring centre y = 53, fan centre 55),
            // so it must not sag towards the bottom of the indicator any more.
            val centre = (rows.first() + rows.last()) / 2f / 3f
            assertEquals("$label is not centred in the fan band", 54f, centre, 4f)
            // …and it must stay clear of the percentage number above it.
            assertTrue("$label reaches into the percentage", rows.first() / 3f > 36f)
        }
    }

    @Test fun networkLabelIsMuchLargerThanTheBatteryPercentage() {
        // The network type replaces the Wi-Fi fan, so it has to read as a prominent
        // label — not as a caption squeezed below the percentage.
        for (label in listOf("4G", "5G")) {
            val b = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = label))
            val rows = (140..250).filter { y -> (100..200).any { x -> Color.alpha(b.getPixel(x, y)) > 128 } }
            assertTrue(rows.isNotEmpty())
            // The glyph is at least 12 units tall (36 px on the 300 px test canvas).
            assertTrue("$label is still too small: ${rows.size} rows", rows.size >= 36)
        }
    }

    @Test fun aLargeNetworkLabelNeverOverlapsThePercentageOrTheArc() {
        // Even at the top of the text scale the label must stay clear of the battery
        // percentage above it and of the battery arc below it.
        val state = IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = "5G")
        val b = render(state, IndicatorConfig(textScale = 250))
        // The percentage occupies the top of the canvas; the label starts below it.
        val labelRows = (140..260).filter { y -> (100..200).any { x -> Color.alpha(b.getPixel(x, y)) > 128 } }
        assertTrue(labelRows.isNotEmpty())
        assertTrue("The label must start below the percentage", labelRows.first() >= 138)
        // The battery arc is drawn on the ring around (50, 53) with radius 44 — its
        // lowest point in the middle column is far below, but the arc's upper ends
        // reach y = 53 + 44 * sin(45°) ≈ 84 units, so the label must end above that.
        assertTrue("The label must clear the battery arc", labelRows.last() < 84 * 3)
    }

    @Test fun dualSimPutsTheMainCardOnTheLeftAndTheSecondaryOnTheRight() {
        val sims = listOf(
            IndicatorSim(slot = 0, phase = SimPhase.READY, level = 4, dataDefault = true),
            IndicatorSim(slot = 1, phase = SimPhase.READY, level = 1)
        )
        val state = IndicatorState.demo.copy(sim = SimPhase.READY, mobileLevel = 4, sims = sims)
        val b = render(state, IndicatorConfig(dualSim = true))
        fun pixel(side: Int, index: Int): Int {
            val radius = 44f
            val angles = floatArrayOf(-33f, -11f, 11f, 33f)
            val rad = Math.toRadians(angles[index].toDouble())
            val x = ((50f + side * radius * Math.cos(rad).toFloat()) * 3).toInt()
            val y = ((53f + radius * Math.sin(rad).toFloat()) * 3).toInt()
            return b.getPixel(x, y)
        }
        // Lit dots are solid, empty ones are a faint tint.
        assertTrue("Left half must show the main card's four bars",
            (0..3).all { Color.alpha(pixel(-1, it)) == 255 })
        assertTrue("Right half must show only the secondary card's first bar",
            Color.alpha(pixel(1, 0)) == 255 && (1..3).all { Color.alpha(pixel(1, it)) < 80 })
    }

    @Test fun aMissingSecondaryCardLeavesTheRightColumnEmpty() {
        val sims = listOf(IndicatorSim(slot = 0, phase = SimPhase.READY, level = 4, dataDefault = true))
        val state = IndicatorState.demo.copy(sim = SimPhase.READY, mobileLevel = 4, sims = sims)
        val b = render(state, IndicatorConfig(dualSim = true))
        fun alpha(side: Int, index: Int): Int {
            val radius = 44f
            val angles = floatArrayOf(-33f, -11f, 11f, 33f)
            val rad = Math.toRadians(angles[index].toDouble())
            val x = ((50f + side * radius * Math.cos(rad).toFloat()) * 3).toInt()
            val y = ((53f + radius * Math.sin(rad).toFloat()) * 3).toInt()
            return Color.alpha(b.getPixel(x, y))
        }
        assertTrue("The main card must keep its bars", (0..3).all { alpha(-1, it) == 255 })
        assertTrue("A switched-off secondary card must leave the right column empty",
            (0..3).all { alpha(1, it) < 80 })
        // With the dual-card view switched off the right column stays empty as well.
        val b2 = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(b2), 300f, 300f, state, IndicatorConfig(dualSim = false), Color.BLACK)
        var empty = true
        for (index in 0..3) {
            val rad = Math.toRadians(floatArrayOf(-33f, -11f, 11f, 33f)[index].toDouble())
            val x = ((50f + 44f * Math.cos(rad).toFloat()) * 3).toInt()
            val y = ((53f + 44f * Math.sin(rad).toFloat()) * 3).toInt()
            if (Color.alpha(b2.getPixel(x, y)) >= 80) empty = false
        }
        assertTrue("The secondary column must stay empty when dual SIM is off", empty)
    }

    @Test fun aDisabledCard1MovesTheSignalToTheCard2Column() {
        // Card 1 is switched off, card 2 is in use: the right column carries the signal.
        val sims = listOf(IndicatorSim(slot = 1, phase = SimPhase.READY, level = 3, dataDefault = true))
        val state = IndicatorState.demo.copy(sim = SimPhase.READY, mobileLevel = 3, sims = sims)
        val b = render(state, IndicatorConfig(dualSim = true))
        fun alpha(side: Int, index: Int): Int {
            val radius = 44f
            val angles = floatArrayOf(-33f, -11f, 11f, 33f)
            val rad = Math.toRadians(angles[index].toDouble())
            val x = ((50f + side * radius * Math.cos(rad).toFloat()) * 3).toInt()
            val y = ((53f + radius * Math.sin(rad).toFloat()) * 3).toInt()
            return Color.alpha(b.getPixel(x, y))
        }
        assertTrue("The card 1 column must stay empty", (0..3).all { alpha(-1, it) < 80 })
        assertTrue("The card 2 column must show three bars",
            (0..2).all { alpha(1, it) == 255 } && alpha(1, 3) < 80)
    }

    @Test fun singleCardViewShowsTheCardInUseInItsOwnColumn() {
        // Card 1 is off, card 2 is in use and the dual-card view is off: the signal must
        // still be visible — in the column of card 2's slot.
        val sims = listOf(IndicatorSim(slot = 1, phase = SimPhase.READY, level = 3, dataDefault = true))
        val state = IndicatorState.demo.copy(sim = SimPhase.READY, mobileLevel = 3, sims = sims)
        val b = render(state, IndicatorConfig(dualSim = false))
        fun alpha(side: Int, index: Int): Int {
            val radius = 44f
            val angles = floatArrayOf(-33f, -11f, 11f, 33f)
            val rad = Math.toRadians(angles[index].toDouble())
            val x = ((50f + side * radius * Math.cos(rad).toFloat()) * 3).toInt()
            val y = ((53f + radius * Math.sin(rad).toFloat()) * 3).toInt()
            return Color.alpha(b.getPixel(x, y))
        }
        assertTrue("The card 1 column must stay empty", (0..3).all { alpha(-1, it) < 80 })
        assertTrue("The card in use must still show its bars",
            (0..2).all { alpha(1, it) == 255 } && alpha(1, 3) < 80)
    }

    @Test fun revisedStatesContactSheet() {
        val states = listOf(
            "充电 · 四格 Wi-Fi" to IndicatorState.demo.copy(battery = 56, batteryPhase = BatteryPhase.CHARGING),
            "Wi-Fi 未连接 · 5G" to IndicatorState.demo.copy(wifi = WifiPhase.OFF),
            "Wi-Fi 未连接 · 4G" to IndicatorState.demo.copy(wifi = WifiPhase.DISCONNECTED, networkType = "4G"),
            "移动数据关闭" to IndicatorState.demo.copy(wifi = WifiPhase.OFF, dataEnabled = false)
        )
        val bitmap = Bitmap.createBitmap(1000, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f }
        for ((row, pair) in states.withIndex()) {
            for (mode in 0..1) {
                p.color = if (mode == 0) Color.WHITE else Color.rgb(21, 25, 27)
                c.drawRect(mode * 500f, row * 180f, (mode + 1) * 500f, (row + 1) * 180f, p)
                p.color = if (mode == 0) Color.BLACK else Color.WHITE
                c.drawText(pair.first, mode * 500f + 20, row * 180f + 30, p)
                for ((index, size) in listOf(66f, 120f).withIndex()) {
                    val saved = c.save()
                    c.translate(mode * 500f + 160 + index * 180, row * 180f + 45)
                    IndicatorRenderer().draw(c, size, size, pair.second, IndicatorConfig(), p.color)
                    c.restoreToCount(saved)
                }
            }
        }
        save(bitmap, "revised-states.png")
    }

    private fun render(state: IndicatorState, config: IndicatorConfig = IndicatorConfig()): Bitmap {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f, state, config, Color.BLACK)
        return bitmap
    }

    /** Pixel of the battery arc (ring centre (50, 53), radius 44) at the given angle. */
    private fun arcPoint(deg: Int): Pair<Int, Int> {
        val rad = Math.toRadians(deg.toDouble())
        return ((50 + 44 * cos(rad)).roundToInt() * 3) to ((53 + 44 * sin(rad)).roundToInt() * 3)
    }

    private fun ink(b: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        var n = 0
        for (y in y0..y1) for (x in x0..x1) if (Color.alpha(b.getPixel(x, y)) > 0) n++
        return n
    }

    /** The rows of a column band that carry any ink; the indicator is 100 units wide. */
    private fun inkRows(b: Bitmap, x0: Int, x1: Int, y0: Int = 0, y1: Int = b.height - 1): List<Int> =
        (y0..y1).filter { y ->
            (x0..x1).any { x -> Color.alpha(b.getPixel(x, y)) > 128 }
        }

    /**
     * Draws the indicator into a padded canvas and measures how far the ink
     * leaves the 100-unit box: [left, top, right, bottom] in pixels.
     */
    private fun spillOutsideBox(state: IndicatorState, config: IndicatorConfig,
        box: Int = 300, pad: Int = 60): IntArray {
        val b = Bitmap.createBitmap(box + pad * 2, box + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.translate(pad.toFloat(), pad.toFloat())
        IndicatorRenderer().draw(c, box.toFloat(), box.toFloat(), state, config, Color.BLACK)
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        for (y in 0 until b.height) for (x in 0 until b.width)
            if (Color.alpha(b.getPixel(x, y)) > 0) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        return intArrayOf(pad - minX, pad - minY, maxX - (pad + box - 1), maxY - (pad + box - 1))
    }

    @Test fun configRangesClampCorruptOrOldPreferences() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test", 0)
        prefs.edit().putInt("size", 1000).putInt("dotSpacing", -100).putInt("verticalOffset", -90).commit()
        val c = IndicatorConfig.read(prefs)
        assertEquals(32, c.size); assertEquals(70, c.dotSpacing); assertEquals(-4, c.verticalOffset)
        assertFalse(c.enabled)
    }

    @Test fun settingsHasWorkingControlsAndCapturesPortrait() {
        captureActivity("settings-light.png", 1179, 2556)
    }

    /**
     * Renders the "已充满紫色" state as a high-resolution transparent PNG, ready to be
     * dropped in as the launcher icon. The state is the full-charge purple (SuperVOOC):
     * battery 100 + FULL phase + SUPER_VOOC protocol, so the number, the bolt and the
     * battery arc all come out in the violet #9C6BFF. The tint is the same violet so the
     * Wi-Fi fan and the signal dots read in the same purple — the whole indicator is one
     * colour, exactly as it appears in the status bar while full and charging.
     */
    @Test fun fullChargePurpleIconContactSheet() {
        val state = IndicatorState.demo.copy(
            battery = 100,
            batteryPhase = BatteryPhase.FULL,
            chargeProtocol = ChargeProtocol.SUPER_VOOC
        )
        // Render the indicator in pure white on a transparent canvas, so the exact glyph
        // positions can be recoloured element-by-element afterwards (the tricolour 1/0/0
        // and the orange bolt are applied in post). The renderer owns the layout — number
        // + bolt centred as a group — so nothing drifts from the in-app preview.
        val size = 1024
        val inset = size * 0.12f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(inset, inset)
        val drawSize = size - inset * 2
        IndicatorRenderer().draw(canvas, drawSize, drawSize, state,
            IndicatorConfig(colors = false), Color.WHITE)
        save(bitmap, "icon-full-charge-purple.png")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h852dp-night-xxhdpi")
    fun settingsDarkTheme() {
        captureActivity("settings-dark.png", 1179, 2556)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w852dp-h393dp-land-xxhdpi")
    fun settingsLandscape() {
        captureActivity("settings-landscape.png", 2556, 1179)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-xhdpi")
    fun settingsLargeTextOnNarrowScreen() {
        RuntimeEnvironment.setFontScale(1.3f)
        captureActivity("settings-large-text.png", 640, 1480)
    }

    private fun captureActivity(name: String, width: Int, height: Int) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val views = descendants(root)
            // The settings page was reduced to switches and colour swatches: every
            // slider is gone, so nothing can be dragged any more. The switches went
            // with them — the page carries no on/off control at all.
            assertEquals(0, views.count { it is SeekBar })
            assertEquals(0, views.count { it is CardSwitch })
            assertEquals(0, views.count { it is Switch })
            assertFalse(views.filterIsInstance<TextView>().any { it.text.toString().contains("启用三合一") })
            // The diagnostics panel is gone as well.
            assertFalse(views.filterIsInstance<TextView>().any { it.text.toString().contains("适配诊断") })
            // The preview state is picked with chips instead of a spinner.
            val chips = views.filterIsInstance<TextView>()
                .filter { it.text.toString() in IndicatorState.previews.map { it.first } }
            assertTrue(chips.size >= 2)
            chips[1].performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            save(bitmap, name)
            val labelViews = views.filterIsInstance<android.widget.TextView>()
            labelViews.filter { it.layout != null }.forEach {
                val layout = it.layout
                for (line in 0 until layout.lineCount) {
                    assertTrue("Clipped label: ${it.text}", layout.getLineWidth(line) <= it.width + 2)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun descendants(group: ViewGroup): List<View> = buildList {
        for (i in 0 until group.childCount) {
            val view = group.getChildAt(i)
            add(view); if (view is ViewGroup) addAll(descendants(view))
        }
    }
    private fun save(bitmap: Bitmap, name: String) {
        val file = File("../DevDoc/qa", name)
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
