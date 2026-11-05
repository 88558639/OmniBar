package io.github.threeinone

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.*
import io.github.threeinone.render.IndicatorRenderer
import io.github.threeinone.ui.CardSwitch
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
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargingMarkerTest {
    @Test fun legacyConflictIsNormalizedAndTheNumberModeSwitchesAreGone() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(IndicatorConfig.FILE, 0)
        prefs.edit().clear().putBoolean("showNumber", true).putBoolean("autoNumber", true)
            .putInt("numberThreshold", 30).putInt("numberScale", 250).commit()
        // The stored conflict is still resolved when the config is read.
        assertFalse(IndicatorConfig.read(prefs).autoNumber)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            fun descendants(v: View): List<View> = listOf(v) +
                if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) }
                else emptyList()
            val views = descendants(controller.get().findViewById(android.R.id.content))
            // The percentage is always part of the new layout, so the settings
            // page no longer offers the "always"/"automatic" number switches.
            val labels = views.filterIsInstance<TextView>().map { it.text.toString() }
            assertFalse(labels.any { it.contains("始终显示电量数字") })
            assertFalse(labels.any { it.contains("低电量自动显示数字") })
            // The master switch is gone: the replacement follows the LSPosed module,
            // so the page no longer offers an "enable" switch at all.
            assertFalse(labels.any { it.contains("启用三合一") })
            assertEquals(0, views.count { it.contentDescription == "启用三合一" })
            // Every switch on the page is one of the hand-drawn card switches; the
            // platform widget is gone for good. Only the two colour toggles are left.
            assertEquals(2, views.count { it is CardSwitch })
            assertEquals(0, views.count { it is Switch })
            assertFalse(prefs.getBoolean("autoNumber", true))
            assertEquals(30, prefs.getInt("numberThreshold", 0))
            assertEquals(250, prefs.getInt("numberScale", 0))
            // …and the replacement is published as enabled, so a fresh install works
            // without an in-app switch.
            assertTrue(prefs.getBoolean("enabled", false))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun modeWritePublishesNoConflictingIntermediateState() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("atomic-number", 0)
        prefs.edit().putBoolean("showNumber", true).commit()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            assertFalse(p.getBoolean("showNumber", false) && p.getBoolean("autoNumber", false))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            IndicatorConfig.setNumberMode(prefs, "autoNumber", true)
            IndicatorConfig.setNumberMode(prefs, "showNumber", true)
            IndicatorConfig.setNumberMode(prefs, "showNumber", false)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun render(state: IndicatorState, config: IndicatorConfig): Bitmap =
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
            IndicatorRenderer().draw(Canvas(it), 300f, 300f, state, config, Color.BLACK)
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

    @Test fun fullChargeShowsTheNumberAtTopAndDrawsTheCompleteArc() {
        val cfg = IndicatorConfig()
        val full = render(IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL), cfg)
        val normal = render(IndicatorState.demo.copy(battery = 100), cfg)

        // The percentage is drawn at the top in the charging colour. No protocol could
        // be read here, so that is the unknown-protocol one.
        val greenAtTop = (0..40).sumOf { y ->
            (0..299).count { x -> full.getPixel(x, y) == cfg.unknownColor }
        }
        assertTrue("Full charge must paint the number in the charging colour", greenAtTop > 500)
        // "100" is three separate digits. The charging bolt is a badge in the top
        // corner, so the scan of the digits stops before it.
        assertEquals(3, inkClusters(full, 0, 195, 0, 40).size)
        // The badge is pinned to the right edge of the indicator and hangs above the
        // signal dots: the last digit ends at x = 78 units (234 px), the badge starts
        // after it and reaches the edge (x = 99.5 units = 298 px).
        val topBand = inkClusters(full, 0, 299, 0, 40)
        assertEquals("The badge must be a cluster of its own", 4, topBand.size)
        assertTrue("The badge must not touch the digits", topBand.last().first > 240)
        assertTrue("The badge must reach the edge of the indicator", topBand.last().last > 290)
        // …and it must stop above the signal dots (top edge y = 23.6 units = 71 px).
        for (x in 280..299) for (y in 71..110)
            assertEquals("The bolt must not reach the signal dots", 0,
                Color.alpha(full.getPixel(x, y)))

        // The bottom arc is complete, from 135° (lower left) to 45° (lower right).
        for (deg in 135 downTo 45 step 5) {
            val rad = Math.toRadians(deg.toDouble())
            val x = ((50 + 44 * cos(rad)).roundToInt() * 3)
            val y = ((53 + 44 * sin(rad)).roundToInt() * 3)
            assertEquals("Gap in the full arc at $deg°", cfg.unknownColor, full.getPixel(x, y))
        }

        // The network part of the indicator is unaffected by the charging state.
        for (y in 130..200) for (x in 20..280)
            assertEquals("Network geometry changed", normal.getPixel(x, y), full.getPixel(x, y))

        // The host background is never erased.
        val background = Color.rgb(37, 42, 47)
        val opaque = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(opaque)
        canvas.drawColor(background)
        IndicatorRenderer().draw(canvas, 300f, 300f,
            IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL),
            IndicatorConfig(), Color.WHITE)
        assertEquals(255, Color.alpha(opaque.getPixel(150, 60)))
        assertEquals(background, opaque.getPixel(15, 15))
    }

    @Test fun chargingAndFullExtremeContactSheet() {
        val cases = listOf(
            "Auto 30 / unplugged" to IndicatorState.demo.copy(battery = 30),
            "Auto 30 / charging" to IndicatorState.demo.copy(battery = 30, batteryPhase = BatteryPhase.CHARGING),
            "Auto 30 / paused" to IndicatorState.demo.copy(battery = 30, batteryPhase = BatteryPhase.PAUSED),
            "Full / Wi-Fi" to IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL),
            "Full / 5G 250%" to IndicatorState.demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL, wifi = WifiPhase.OFF),
            "Always / charging" to IndicatorState.demo.copy(battery = 10, batteryPhase = BatteryPhase.CHARGING)
        )
        val bitmap = Bitmap.createBitmap(1000, cases.size * 170, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f }
        cases.forEachIndexed { row, (label, state) ->
            for (mode in 0..1) {
                paint.color = if (mode == 0) Color.WHITE else Color.rgb(21, 25, 27)
                canvas.drawRect(mode * 500f, row * 170f, (mode + 1) * 500f, (row + 1) * 170f, paint)
                paint.color = if (mode == 0) Color.BLACK else Color.WHITE
                canvas.drawText(label, mode * 500f + 12, row * 170f + 25, paint)
                for ((index, size) in listOf(16f, 32f, 48f, 110f).withIndex()) {
                    val save = canvas.save()
                    canvas.translate(mode * 500f + 25 + index * 110, row * 170f + 48)
                    IndicatorRenderer().draw(canvas, size, size, state,
                        IndicatorConfig(autoNumber = row != 5, showNumber = row == 5,
                            numberThreshold = 100, numberScale = 250, textScale = 250, stroke = 160), paint.color)
                    canvas.restoreToCount(save)
                }
            }
        }
        val file = File("../DevDoc/qa/visual-0.1.8.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
