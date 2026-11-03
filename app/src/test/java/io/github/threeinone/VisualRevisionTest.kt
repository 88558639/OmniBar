package io.github.threeinone

import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import io.github.threeinone.render.IndicatorRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualRevisionTest {
    @Test fun number250AndAutomaticPreferencesPersistAndClamp() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("numbers250", 0)
        prefs.edit().putInt("numberScale", 250).putBoolean("autoNumber", true)
            .putInt("numberThreshold", 35).commit()
        val config = IndicatorConfig.read(prefs)
        assertEquals(250, config.numberScale)
        assertTrue(config.autoNumber)
        assertEquals(35, config.numberThreshold)
        prefs.edit().putInt("numberScale", 999).putInt("numberThreshold", -10).commit()
        assertEquals(250, IndicatorConfig.read(prefs).numberScale)
        assertEquals(1, IndicatorConfig.read(prefs).numberThreshold)
    }

    @Test fun automaticRenderingMatchesExplicitModeIncludingCharging() {
        for (phase in listOf(BatteryPhase.NORMAL, BatteryPhase.CHARGING, BatteryPhase.PAUSED)) {
            for (level in listOf(29, 30, 31)) {
                val state = IndicatorState.demo.copy(battery = level, batteryPhase = phase)
                assertTrue(render(state, IndicatorConfig(autoNumber = true, numberThreshold = 30))
                    .sameAs(render(state, IndicatorConfig(showNumber = level <= 30))))
            }
        }
    }

    @Test fun largeNumbersGrowUpToTheSixtyUnitWidthCap() {
        // The number is drawn on the 100-unit canvas with a 60-unit maximum width.
        val state = IndicatorState.demo.copy(battery = 76, airplane = true)
        val small = render(state, IndicatorConfig(numberScale = 100))
        val large = render(state, IndicatorConfig(numberScale = 250))
        fun glyphInk(b: Bitmap) = (0..85).sumOf { y ->
            (0..299).count { x -> Color.alpha(b.getPixel(x, y)) > 0 }
        }
        assertTrue("A larger number scale must not shrink the glyph",
            glyphInk(large) >= glyphInk(small))
        // The glyph is clamped by the canvas height, so it must never touch the top row.
        for (b in listOf(small, large)) {
            var top = -1
            for (y in 0..100) {
                if ((0..299).any { x -> Color.alpha(b.getPixel(x, y)) > 0 }) { top = y; break }
            }
            assertTrue("Number is clipped at the top of the canvas", top >= 1)
        }
        // The glyph may never grow past the 60-unit (180 px) cap.
        assertTrue("Number exceeds the maximum width", glyphWidth(large) <= 180)
        assertTrue("Number exceeds the maximum width", glyphWidth(render(
            IndicatorState.demo.copy(battery = 100, airplane = true), IndicatorConfig(numberScale = 250))) <= 180)
        // The baseline sits at y = 28 units, so the glyph never reaches below it.
        for (b in listOf(small, large)) {
            var lowest = -1
            for (y in 0..100) if ((0..299).any { x -> Color.alpha(b.getPixel(x, y)) > 0 }) lowest = y
            assertTrue("Number reaches below the baseline: $lowest", lowest in 84..86)
        }
        // A single digit is centred on x = 50.
        val single = render(IndicatorState.demo.copy(battery = 5, airplane = true),
            IndicatorConfig(numberScale = 115))
        val columns = (0..299).filter { x -> (0..85).any { y -> Color.alpha(single.getPixel(x, y)) > 0 } }
        assertTrue("Number is not centred", Math.abs((columns.first() + columns.last()) / 2 - 150) <= 3)
    }

    @Test fun automaticNumberContactSheet() {
        val sheet = Bitmap.createBitmap(1000, 960, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f }
        val cases = listOf(
            Triple("100 · 250%", IndicatorState.demo.copy(battery = 100), IndicatorConfig(showNumber = true, numberScale = 250)),
            Triple("8 · 250%", IndicatorState.demo.copy(battery = 8), IndicatorConfig(showNumber = true, numberScale = 250)),
            Triple("31% · 自动隐藏", IndicatorState.demo.copy(battery = 31), IndicatorConfig(autoNumber = true, numberThreshold = 30, numberScale = 250)),
            Triple("30% · 自动显示", IndicatorState.demo.copy(battery = 30), IndicatorConfig(autoNumber = true, numberThreshold = 30, numberScale = 250)),
            Triple("充电 30% · 闪电优先", IndicatorState.demo.copy(battery = 30, batteryPhase = BatteryPhase.CHARGING), IndicatorConfig(autoNumber = true, numberThreshold = 30, numberScale = 250)),
            Triple("充电 31% · 恢复闪电", IndicatorState.demo.copy(battery = 31, batteryPhase = BatteryPhase.CHARGING), IndicatorConfig(autoNumber = true, numberThreshold = 30, numberScale = 250))
        )
        cases.forEachIndexed { row, (name, state, config) ->
            for (mode in 0..1) {
                p.color = if (mode == 0) Color.WHITE else Color.rgb(21, 25, 27)
                c.drawRect(mode * 500f, row * 160f, (mode + 1) * 500f, (row + 1) * 160f, p)
                p.color = if (mode == 0) Color.BLACK else Color.WHITE
                c.drawText(name, mode * 500f + 12, row * 160f + 24, p)
                for ((index, size) in listOf(66f, 110f).withIndex()) {
                    val save = c.save()
                    c.translate(mode * 500f + 175 + index * 160, row * 160f + 40)
                    IndicatorRenderer().draw(c, size, size, state, config, p.color)
                    c.restoreToCount(save)
                }
            }
        }
        save(sheet, "visual-0.1.4.png")
    }

    private fun render(state: IndicatorState, config: IndicatorConfig, tint: Int = Color.BLACK): Bitmap =
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
            IndicatorRenderer().draw(Canvas(it), 300f, 300f, state, config, tint)
        }

    /** Width in pixels of the number drawn in the top band of the canvas. */
    private fun glyphWidth(b: Bitmap): Int {
        val columns = (0..299).filter { x -> (0..85).any { y -> Color.alpha(b.getPixel(x, y)) > 0 } }
        return if (columns.isEmpty()) 0 else columns.last() - columns.first() + 1
    }

    /** Contiguous groups of columns that carry ink in the given band. */
    private fun inkClusters(b: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int, threshold: Int = 128): List<IntRange> {
        val columns = (x0..x1).filter { x -> (y0..y1).any { y -> Color.alpha(b.getPixel(x, y)) > threshold } }
        if (columns.isEmpty()) return emptyList()
        val groups = mutableListOf<IntRange>()
        var start = columns.first(); var previous = columns.first()
        for (x in columns.drop(1)) {
            if (x > previous + 1) { groups += start..previous; start = x }
            previous = x
        }
        groups += start..previous
        return groups
    }

    @Test fun numbersDoNotShrinkOrShiftWifiAndSignalDots() {
        for (phase in listOf(BatteryPhase.NORMAL, BatteryPhase.CHARGING)) {
            val state = IndicatorState.demo.copy(batteryPhase = phase)
            val off = render(state, IndicatorConfig())
            val on = render(state, IndicatorConfig(showNumber = true))
            for (y in 90 until 300) for (x in 0 until 300)
                assertEquals("Geometry shifted at $x,$y", off.getPixel(x, y), on.getPixel(x, y))
        }
    }

    @Test fun chargingOverridesNumberAndKeepsGreen() {
        val state = IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.CHARGING)
        for (tint in listOf(Color.BLACK, Color.WHITE)) {
            val b = render(state, IndicatorConfig(showNumber = true), tint)
            val numberPixels = (18..48).flatMap { y -> (115..185).map { x -> b.getPixel(x, y) } }
            assertEquals(0, numberPixels.count { it == tint })
            assertTrue(b.sameAs(render(state, IndicatorConfig(), tint)))
            assertTrue("Arc must retain charging green", (0 until 300).any { y ->
                (0 until 300).any { x -> b.getPixel(x, y) == 0xff1cb753.toInt() }
            })
        }
    }

    @Test fun batteryNumberIsDrawnAtTheTopAndKeepsTheBackgroundIntact() {
        // On a transparent canvas the top band carries the percentage glyphs only.
        val transparent = render(IndicatorState.demo, IndicatorConfig(), Color.WHITE)
        val glyphs = (0..85).sumOf { y ->
            (0..299).count { x -> Color.alpha(transparent.getPixel(x, y)) > 0 }
        }
        assertTrue("Battery number is missing", glyphs > 1000)
        // "76" is two separate digits, so the label shows two ink clusters.
        assertEquals(2, inkClusters(transparent, 0, 299, 0, 40).size)

        // On a host background the number is painted in the tint colour and
        // everything around it keeps the background untouched.
        val background = Color.rgb(41, 52, 62)
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(background)
        IndicatorRenderer().draw(c, 300f, 300f, IndicatorState.demo, IndicatorConfig(), Color.WHITE)
        val white = (0..85).sumOf { y ->
            (0..299).count { x -> b.getPixel(x, y) == 0xffffffff.toInt() }
        }
        assertTrue("Battery number is not painted in the tint colour", white > 1000)
        assertEquals(background, b.getPixel(15, 15))
        assertEquals(background, b.getPixel(285, 15))
    }

    @Test fun unknownNumberDoesNotInventBatteryProgress() {
        val unknown = IndicatorState.demo.copy(battery = 80, batteryPhase = BatteryPhase.UNKNOWN)
        val negative = unknown.copy(battery = -1)
        assertFalse(render(unknown, IndicatorConfig(showNumber = true))
            .sameAs(render(negative, IndicatorConfig(showNumber = true))))
        assertTrue(render(unknown, IndicatorConfig(showNumber = true))
            .sameAs(render(unknown.copy(batteryPhase = BatteryPhase.NORMAL), IndicatorConfig(showNumber = true))))
    }

    @Test fun networkScale250ActuallyEnlargesGlyphsAndPersists() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("scale250", 0)
        prefs.edit().putInt("textScale", 250).commit()
        assertEquals(250, IndicatorConfig.read(prefs).textScale)
        prefs.edit().putInt("textScale", 900).commit()
        assertEquals(250, IndicatorConfig.read(prefs).textScale)
        for (label in listOf("2G", "3G", "4G", "5G")) {
            val state = IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = label)
            fun ink(b: Bitmap) = (90..195).sumOf { y ->
                (65..235).count { x -> Color.alpha(b.getPixel(x, y)) > 128 }
            }
            val normal = ink(render(state, IndicatorConfig(textScale = 150)))
            val large = ink(render(state, IndicatorConfig(textScale = 250)))
            assertTrue("$label must grow, not be silently width-clamped", large > normal * 2)
        }
    }

    @Test fun airplaneHasRoundedNoseSweptWingsAndSeparateTailInBothModes() {
        for (wifi in listOf(WifiPhase.OFF, WifiPhase.CONNECTED)) {
            val b = render(IndicatorState.demo.copy(airplane = true, wifi = wifi), IndicatorConfig())
            // Rounded nose: the tip starts narrow and widens towards the body.
            val tip = inkClusters(b, 100, 200, 125, 125).singleOrNull()
            val body = inkClusters(b, 100, 200, 140, 140).singleOrNull()
            assertNotNull("Nose is not a single shape", tip)
            assertNotNull("Body is not a single shape", body)
            assertTrue("Nose is not rounded: tip=${tip!!.count()} body=${body!!.count()}",
                tip.count() <= 8 && body.count() > tip.count())
            // Swept wings: at the wing row the wings and the fuselage are separate.
            assertEquals(listOf(116..128, 143..156, 171..183), inkClusters(b, 100, 200, 174, 174))
            // Separate tail: two distinct fins with a gap in the middle.
            assertEquals(listOf(136..142, 157..163), inkClusters(b, 100, 200, 196, 196))
            assertEquals(0, Color.alpha(b.getPixel(150, 196)))
            // The plane ends well above the battery arc and never reaches the
            // bottom edge of the icon.
            for (y in 199..241) for (x in 100..200)
                assertEquals("Airplane reaches into the arc", 0, Color.alpha(b.getPixel(x, y)))
            for (x in 0 until 300) {
                val bottom = Color.alpha(b.getPixel(x, 299))
                if (bottom > 0) assertTrue("Unexpected element at the bottom edge: $x", x in 120..180)
            }
        }
    }

    @Test fun updatedStatesAndExtremeParametersContactSheet() {
        val numbered = IndicatorConfig(showNumber = true)
        val cases = listOf(
            Triple("数字 76", IndicatorState.demo, numbered),
            Triple("数字 100 · 最大字号", IndicatorState.demo.copy(battery = 100), numbered.copy(numberScale = 150)),
            Triple("数字 0 · 低电量", IndicatorState.demo.copy(battery = 0), numbered),
            Triple("数字 + 充电", IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING), numbered),
            Triple("原充电闪电", IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING), IndicatorConfig()),
            Triple("飞行模式", IndicatorState.demo.copy(wifi = WifiPhase.OFF, airplane = true), numbered),
            Triple("飞行模式 + Wi-Fi", IndicatorState.demo.copy(airplane = true), numbered),
            Triple("5G · 250%", IndicatorState.demo.copy(wifi = WifiPhase.OFF), numbered.copy(textScale = 250)),
            Triple("4G · 极端参数", IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = "4G"),
                numbered.copy(textScale = 250, numberScale = 150, stroke = 160, dotScale = 140, dotSpacing = 125))
        )
        val sheet = Bitmap.createBitmap(1000, cases.size * 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f }
        cases.forEachIndexed { row, (name, state, config) ->
            for (mode in 0..1) {
                p.color = if (mode == 0) Color.WHITE else Color.rgb(21, 25, 27)
                canvas.drawRect(mode * 500f, row * 160f, (mode + 1) * 500f, (row + 1) * 160f, p)
                p.color = if (mode == 0) Color.BLACK else Color.WHITE
                canvas.drawText(name, mode * 500f + 12, row * 160f + 24, p)
                for ((index, size) in listOf(66f, 110f).withIndex()) {
                    val save = canvas.save()
                    canvas.translate(mode * 500f + 175 + index * 160, row * 160f + 40)
                    IndicatorRenderer().draw(canvas, size, size, state, config, p.color)
                    canvas.restoreToCount(save)
                }
            }
            val b = render(state, config)
            for (i in 0 until 300) {
                assertEquals("Left overflow", 0, Color.alpha(b.getPixel(0, i)))
                assertEquals("Right overflow", 0, Color.alpha(b.getPixel(299, i)))
            }
            // The battery arc is the only element that reaches the bottom edge.
            for (x in 0 until 300) {
                if (Color.alpha(b.getPixel(x, 299)) > 0)
                    assertTrue("Unexpected element at the bottom edge: $x", x in 100..200)
            }
        }
        save(sheet, "visual-0.1.3.png")
    }

    @Test fun launcherUsesNewAdaptiveArtworkAndMonochromeLayer() {
        val context = RuntimeEnvironment.getApplication()
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.monochrome)
        val b = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(225, 228, 233))
        icon.setBounds(20, 20, 300, 300)
        icon.draw(c)
        icon.monochrome!!.apply {
            setBounds(340, 20, 620, 300)
            setTint(Color.rgb(55, 76, 90))
            draw(c)
        }
        save(b, "launcher-0.1.3.png")
    }

    private fun save(bitmap: Bitmap, name: String) {
        val file = File("../DevDoc/qa", name)
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
