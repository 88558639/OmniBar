package io.github.threeinone

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import io.github.threeinone.render.IndicatorRenderer
import io.github.threeinone.ui.MainActivity
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
        fun boltInk(state: IndicatorState): Int {
            val b = render(state)
            return (0 until 36).sumOf { y -> (250..285).count { x -> Color.alpha(b.getPixel(x, y)) > 0 } }
        }
        assertEquals("No bolt while discharging", 0, boltInk(IndicatorState.demo))
        assertTrue("Bolt missing while charging",
            boltInk(IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING)) > 50)
        assertTrue("Bolt missing when full",
            boltInk(IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL)) > 50)

        // The bolt uses the charging colour and never erases the host background.
        val composed = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(composed)
        c.drawColor(Color.rgb(21, 25, 27))
        IndicatorRenderer().draw(c, 300f, 300f,
            IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING), IndicatorConfig(), Color.BLACK)
        assertEquals("Background must not be erased", Color.rgb(21, 25, 27), composed.getPixel(15, 15))
        assertEquals(0xff1cb753.toInt(), composed.getPixel(267, 10))
    }

    @Test fun wifiHasFourSeparatelyLitLevels() {
        // The fan sits at (50, 62): a centre dot plus three arcs whose apexes are
        // at y = 52 (r = 10), y = 44 (r = 18) and y = 37 (r = 25).
        val probes = listOf(186 to 62, 156 to 52, 132 to 44, 111 to 37)
        for (level in 0..4) {
            val b = render(IndicatorState.demo.copy(wifiLevel = level), IndicatorConfig(wifiScale = 115))
            probes.forEachIndexed { i, (y, unit) ->
                val alpha = Color.alpha(b.getPixel(150, y))
                if (i < level) assertEquals("Active Wi-Fi band $i (y=$unit) at level $level", 255, alpha)
                else assertEquals("Inactive Wi-Fi band $i (y=$unit) at level $level", 55, alpha)
            }
        }
    }

    @Test fun networkTypeTextIsShownOnlyWhenSimIsReady() {
        val ready4g = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = "4G"))
        val ready5g = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = "5G"))
        assertFalse("The network type must be rendered when the SIM is ready", ready4g.sameAs(ready5g))
        assertTrue("No network type glyphs", ink(ready4g, 110, 190, 160, 190) > 200)

        val noSim4g = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF,
            sim = SimPhase.NO_SIGNAL, networkType = "4G"))
        val noSim5g = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF,
            sim = SimPhase.NO_SIGNAL, networkType = "5G"))
        assertTrue("No network type without a ready SIM", noSim4g.sameAs(noSim5g))
        assertEquals("Only the placeholder may remain", 0, ink(noSim4g, 110, 190, 160, 190))
        // The fallback is the small "--" placeholder in the middle of the fan.
        assertTrue("Placeholder missing", ink(noSim4g, 110, 190, 150, 160) > 0)
    }

    @Test fun networkLabelIsCentredOnTheFanPosition() {
        for (label in listOf("2G", "3G", "4G", "5G")) {
            val b = render(IndicatorState.demo.copy(wifi = WifiPhase.OFF, networkType = label))
            val rows = (90..195).filter { y -> (90..210).any { x -> Color.alpha(b.getPixel(x, y)) > 128 } }
            assertTrue(rows.isNotEmpty())
            // The label is centred on y = 57 units.
            assertEquals(57f, (rows.first() + rows.last()) / 6f, 1f)
        }
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
            assertEquals(11, views.count { it is SeekBar })
            assertEquals(7, views.count { it is Switch })
            val spinner = views.filterIsInstance<Spinner>().single()
            spinner.setSelection(1)
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
