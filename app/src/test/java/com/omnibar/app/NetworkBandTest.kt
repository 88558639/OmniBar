package com.omnibar.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.omnibar.app.config.IndicatorConfig
import com.omnibar.app.model.IndicatorState
import com.omnibar.app.model.SimPhase
import com.omnibar.app.model.WifiPhase
import com.omnibar.app.render.IndicatorRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The marks in the middle band of the indicator.
 *
 * The band carries the Wi-Fi fan when there is one and the cellular state when there is
 * not, so every mark in it has to be legible on its own and must not run into its
 * neighbours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkBandTest {

    /** The indicator is a 100-unit box; the test bitmap is 300 px wide. */
    private val px = 3

    private fun render(
        state: IndicatorState, config: IndicatorConfig = IndicatorConfig(),
        connectPhase: Float = 0f
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f, state, config, Color.BLACK,
            0f, connectPhase)
        return bitmap
    }

    /** Ink pixel count inside a unit box. */
    private fun ink(b: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): Int {
        var n = 0
        for (y in (y0 * px).toInt()..(y1 * px).toInt()) {
            for (x in (x0 * px).toInt()..(x1 * px).toInt()) {
                if (Color.alpha(b.getPixel(x, y)) > 0) n++
            }
        }
        return n
    }

    /** The ink bounding box inside a unit box, in units — null when there is no ink. */
    private fun inkBox(b: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): FloatArray? {
        var minX = Float.MAX_VALUE; var maxX = -1f; var minY = Float.MAX_VALUE; var maxY = -1f
        for (y in (y0 * px).toInt()..(y1 * px).toInt()) {
            for (x in (x0 * px).toInt()..(x1 * px).toInt()) {
                if (Color.alpha(b.getPixel(x, y)) > 0) {
                    if (x < minX) minX = x.toFloat()
                    if (x > maxX) maxX = x.toFloat()
                    if (y < minY) minY = y.toFloat()
                    if (y > maxY) maxY = y.toFloat()
                }
            }
        }
        if (maxX < 0) return null
        return floatArrayOf(minX / px, minY / px, maxX / px, maxY / px)
    }

    /** The strongest alpha inside a unit box. */
    private fun peakAlpha(b: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): Int {
        var a = 0
        for (y in (y0 * px).toInt()..(y1 * px).toInt()) {
            for (x in (x0 * px).toInt()..(x1 * px).toInt()) {
                val v = Color.alpha(b.getPixel(x, y))
                if (v > a) a = v
            }
        }
        return a
    }

    /**
     * The bounding box of the solid ink inside a unit box, in units.
     *
     * The faded fan counts as ink too, so the mark itself can only be measured by
     * the pixels that are actually solid.
     */
    private fun solidBox(b: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): FloatArray? {
        var minX = Float.MAX_VALUE; var maxX = -1f; var minY = Float.MAX_VALUE; var maxY = -1f
        for (y in (y0 * px).toInt()..(y1 * px).toInt()) {
            for (x in (x0 * px).toInt()..(x1 * px).toInt()) {
                if (Color.alpha(b.getPixel(x, y)) >= 200) {
                    if (x < minX) minX = x.toFloat()
                    if (x > maxX) maxX = x.toFloat()
                    if (y < minY) minY = y.toFloat()
                    if (y > maxY) maxY = y.toFloat()
                }
            }
        }
        if (maxX < 0) return null
        return floatArrayOf(minX / px, minY / px, maxX / px, maxY / px)
    }

    private val demo get() = IndicatorState.demo

    // ------------------------------------------------------------------ Wi-Fi markers

    /**
     * The mark belongs to the fan: the fan fades out and the mark sits in its middle.
     * A mark beside the fan had no room, and the two merged into one blob.
     */
    @Test fun theWifiMarkSitsInTheMiddleOfTheFan() {
        for (phase in listOf(WifiPhase.NO_INTERNET, WifiPhase.CAPTIVE)) {
            val marked = render(demo.copy(wifi = phase))
            // The fan's own box, so that only the mark is measured.
            val box = inkBox(marked, 30f, 70f, 38f, 78f)
            assertNotNull("$phase: the mark is missing", box)
            val centreX = (box!![0] + box[2]) / 2f
            val centreY = (box!![1] + box[3]) / 2f
            assertEquals("$phase: the mark is not centred on the fan", 50f, centreX, 2f)
            assertEquals("$phase: the mark is not centred in the fan",
                IndicatorRenderer.WIFI_MARK_TOP + IndicatorRenderer.WIFI_MARK_HEIGHT / 2f,
                centreY, 5f)
        }
    }

    /**
     * The mark has to be big enough to notice at all.
     *
     * At 28 units the "!" and "?" were there but read as a speck, so the glyph now
     * fills the height of the fan. This test keeps it from shrinking back.
     */
    /**
     * The mark has to be big enough to notice at all.
     *
     * At 28 units the "!" and "?" were there but read as a speck. The glyph is now
     * scaled to fill the slot from the top of the outer arc down to the dot, so the
     * test pins that slot rather than a point size — the fonts differ per device.
     */
    @Test fun theWifiMarkIsBigEnoughToNotice() {
        val bang = solidBox(render(demo.copy(wifi = WifiPhase.NO_INTERNET)), 34f, 66f, 34f, 80f)
        val question = solidBox(render(demo.copy(wifi = WifiPhase.CAPTIVE)), 34f, 66f, 34f, 80f)
        assertNotNull("The ! is missing", bang)
        assertNotNull("The ? is missing", question)
        // It fills the slot: from the top of the outer arc down to the dot.
        assertEquals("The ! does not fill the slot: ${bang!!.toList()}",
            IndicatorRenderer.WIFI_MARK_HEIGHT, bang[3] - bang[1], 3f)
        assertEquals("The ? does not fill the slot: ${question!!.toList()}",
            IndicatorRenderer.WIFI_MARK_HEIGHT, question[3] - question[1], 3f)
        // The "?" is wider than the stem of the "!", but it still has to be a glyph.
        assertTrue("The ? is too narrow: ${question.toList()}", question[2] - question[0] >= 12f)
        // The mark has to reach over the top of the outer arc, so that it covers the
        // whole signal stack instead of hiding between the arcs.
        assertEquals("The ! does not start at the outer arc: ${bang.toList()}",
            IndicatorRenderer.WIFI_MARK_TOP, bang[1], 2f)
        assertEquals("The ? does not start at the outer arc: ${question.toList()}",
            IndicatorRenderer.WIFI_MARK_TOP, question[1], 2f)
        // Both have to stay inside the fan and off the signal dots.
        assertTrue("The ! is off-centre: ${bang.toList()}", bang[0] > 40f && bang[2] < 60f)
        assertTrue("The ? is off-centre: ${question.toList()}", question[0] > 36f && question[2] < 64f)
    }

    /**
     * The connecting state is an animation: three bars climb one after another.
     *
     * The "..." said "connecting" without saying whether anything was going on, so the
     * peak now travels from the left bar to the right one.
     */
    @Test fun theConnectingBarsClimbOneAfterAnother() {
        val first = render(demo.copy(wifi = WifiPhase.CONNECTING), connectPhase = 1f / 6f)
        val second = render(demo.copy(wifi = WifiPhase.CONNECTING), connectPhase = 1f / 2f)
        val third = render(demo.copy(wifi = WifiPhase.CONNECTING), connectPhase = 5f / 6f)
        // The left bar is at its peak first, the middle one next, the right one last.
        assertTrue("The left bar does not lead", barTop(first, 0) < barTop(first, 1))
        assertTrue("The middle bar does not follow", barTop(second, 1) < barTop(second, 2))
        assertTrue("The right bar does not finish", barTop(third, 2) < barTop(third, 0))
        // The bars are solid — the fan behind them is the pale one.
        assertTrue("The bars are not solid",
            peakAlpha(first, 36f, 64f, 50f, 74f) >= 250)
        // The animation really moves.
        assertFalse("The connecting mark does not move", first.sameAs(second))
    }

    /** The top edge of one of the three connecting bars, in units. */
    private fun barTop(b: Bitmap, index: Int): Float {
        val total = IndicatorRenderer.CONNECT_BAR_WIDTH * 3f + IndicatorRenderer.CONNECT_BAR_GAP * 2f
        val left = 50f - total / 2f + index * (IndicatorRenderer.CONNECT_BAR_WIDTH + IndicatorRenderer.CONNECT_BAR_GAP)
        for (y in (44 * px).toInt()..(76 * px).toInt()) {
            for (x in (left * px).toInt()..((left + IndicatorRenderer.CONNECT_BAR_WIDTH) * px).toInt()) {
                if (Color.alpha(b.getPixel(x, y)) > 128) return y.toFloat() / px
            }
        }
        return Float.MAX_VALUE
    }

    /** The faded fan is what says "this is a Wi-Fi problem". */
    @Test fun theFanFadesWhenItCarriesAMark() {
        val plain = render(demo.copy(wifi = WifiPhase.CONNECTED))
        val marked = render(demo.copy(wifi = WifiPhase.NO_INTERNET))
        // A point on the outer arc's left shoulder — away from the mark in the middle.
        val x = ((50f - 25f * 0.866f) * px).toInt()
        val y = ((70f - 25f * 0.5f) * px).toInt()
        val plainAlpha = Color.alpha(plain.getPixel(x, y))
        val markedAlpha = Color.alpha(marked.getPixel(x, y))
        assertTrue("The arc is missing", plainAlpha > 200)
        assertTrue("The fan did not fade: $plainAlpha -> $markedAlpha",
            markedAlpha < plainAlpha * IndicatorRenderer.WIFI_MARKED_ALPHA_RATIO + 8)
    }

    /** The mark itself keeps the full tint — it is the loud part of the glyph. */
    @Test fun theWifiMarkKeepsTheFullTint() {
        val marked = render(demo.copy(wifi = WifiPhase.NO_INTERNET))
        val alpha = peakAlpha(marked, 40f, 60f, 42f, 76f)
        assertTrue("The mark is not solid: $alpha", alpha >= 250)
    }

    /** The three marks must be told apart from each other. */
    @Test fun theWifiMarkersAreDistinguishable() {
        val noInternet = render(demo.copy(wifi = WifiPhase.NO_INTERNET))
        val captive = render(demo.copy(wifi = WifiPhase.CAPTIVE))
        val connecting = render(demo.copy(wifi = WifiPhase.CONNECTING))
        assertFalse("! and ? look the same", noInternet.sameAs(captive))
        assertFalse("! and ... look the same", noInternet.sameAs(connecting))
        assertFalse("? and ... look the same", captive.sameAs(connecting))
    }

    // ------------------------------------------------------------------ cellular marks

    /** A phone without signal needs a mark that is still readable in the status bar. */
    @Test fun theNoSignalPlaceholderIsBigEnough() {
        val noSignal = render(demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.NO_SIGNAL))
        val box = inkBox(noSignal, 20f, 80f, 40f, 70f)
        assertNotNull("The placeholder is missing", box)
        // Two solid bars: wide enough to read as a pair, thick enough not to vanish.
        assertTrue("The placeholder is too narrow: ${box!!.toList()}", box[2] - box[0] >= 40f)
        assertTrue("The placeholder is too thin: ${box.toList()}", box[3] - box[1] >= 6f)
        // It must not look like a network type: a dash pair has no ascender.
        assertTrue("The placeholder is too tall: ${box.toList()}", box[3] - box[1] <= 12f)
        // It must stay clear of the signal dot columns.
        assertTrue("The placeholder runs into the signal dots: ${box.toList()}", box[0] > 12f)
        assertTrue("The placeholder runs into the signal dots: ${box.toList()}", box[2] < 88f)
    }

    /**
     * The connecting wave has to fill the fan it stands in.
     *
     * The bars were thin enough to read as a hairline at status bar size, so the whole
     * group is wider and taller now.
     */
    @Test fun theConnectingWaveFillsTheFan() {
        val bars = render(demo.copy(wifi = WifiPhase.CONNECTING), connectPhase = 1f / 6f)
        // Only the solid bars — the faded fan behind them is not part of the wave.
        val box = solidBox(bars, 25f, 75f, 38f, 78f)
        assertNotNull("The wave is missing", box)
        val width = box!![2] - box[0]
        assertTrue("The wave is too narrow: ${box.toList()}", width >= 30f)
        // The tallest bar reaches up to the top of the fan.
        val top = barTop(bars, 0)
        assertTrue("The wave is too flat: $top", top <= 48f)
        // And it must not spill out of the fan either.
        assertTrue("The wave is off-centre: ${box.toList()}", box[0] > 30f && box[2] < 70f)
    }

    /** An emergency-only SIM gets its own mark — "--" said nothing about it. */
    @Test fun anEmergencyOnlySimShowsSos() {
        val emergency = render(demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.EMERGENCY))
        val noSignal = render(demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.NO_SIGNAL))
        assertFalse("The emergency mark looks like the placeholder", emergency.sameAs(noSignal))
        val box = inkBox(emergency, 20f, 80f, 40f, 70f)
        assertNotNull("The SOS mark is missing", box)
        // "SOS" is three letters: it has to be as wide as a network type.
        assertTrue("SOS is too narrow: ${box!!.toList()}", box[2] - box[0] >= 25f)
        assertTrue("SOS is too flat: ${box.toList()}", box[3] - box[1] >= 12f)
    }

    /**
     * A missing, a disabled and a locked SIM all read the same: a faint "sim". They
     * share one preview for that reason.
     */
    @Test fun aBrokenSimShowsAFaintSim() {
        val absent = render(demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.ABSENT))
        val disabled = render(demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.DISABLED))
        val locked = render(demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.LOCKED))
        assertTrue("A disabled SIM looks different", absent.sameAs(disabled))
        assertTrue("A locked SIM looks different", absent.sameAs(locked))
        // It is a remark, not a headline: it is drawn pale.
        val alpha = peakAlpha(absent, 20f, 80f, 40f, 70f)
        assertTrue("The sim mark is too strong: $alpha", alpha in 1..(IndicatorRenderer.SIM_DIM_ALPHA + 8))
        // …but it still has to be readable.
        val box = inkBox(absent, 20f, 80f, 40f, 70f)
        assertNotNull("The sim mark is missing", box)
        assertTrue("The sim mark is too narrow: ${box!!.toList()}", box[2] - box[0] >= 15f)
        // It may fill the ring, but it must not reach the signal dots.
        assertTrue("The sim mark runs into the signal dots: ${box.toList()}", box[0] > 12f)
        assertTrue("The sim mark runs into the signal dots: ${box.toList()}", box[2] < 88f)
    }

    /**
     * The dual-SIM preview shows two plain columns. The roaming colour is a leftover
     * from a setting that is gone, so the preview must not wear it either.
     */
    @Test fun theDualSimPreviewUsesThePlainTintOnBothSides() {
        val state = IndicatorState.previews.first { it.first == "双卡信号" }.second
        assertTrue("The second card is marked as roaming", state.sims.none { it.roaming })
        val b = render(state)
        // The right column of the ring: the roaming colour would be blue here.
        var blue = 0
        for (y in 20..60) for (x in 84..99) {
            val p = b.getPixel(x * px, y * px)
            if (Color.blue(p) > Color.red(p) + 30) blue++
        }
        assertEquals("The right column wears the roaming colour", 0, blue)
    }

    /** States that only made the preview list longer are gone. */
    @Test fun theTrimmedPreviewsAreGone() {
        val names = IndicatorState.previews.map { it.first }
        assertFalse("The preview is still there", names.contains("飞行模式 · Wi-Fi"))
        assertFalse("The preview is still there", names.contains("漫游"))
        assertFalse("The preview is still there", names.contains("数据上下行"))
        assertFalse("The preview is still there", names.contains("Wi-Fi 关闭 · 5G"))
        assertFalse("The preview is still there", names.contains("Wi-Fi 未连接 · 4G"))
        assertFalse("The preview is still there", names.contains("移动数据关闭"))
        // An unknown battery is not a state worth showing: the number is simply "?"
        // and the arc stays empty.
        assertFalse("The preview is still there", names.contains("电量未知"))
    }

    /**
     * Every colour of the state palette needs a preview of its own, so that the page
     * shows what the status bar really does.
     */
    @Test fun everyBatteryColourHasItsPreview() {
        val names = IndicatorState.previews.map { it.first }
        assertTrue("The low battery preview is missing", names.contains("低电量"))
        assertTrue("The critical battery preview is missing", names.contains("严重低电量"))
        assertTrue("The power saver preview is missing", names.contains("省电模式"))
        val config = IndicatorConfig()
        val critical = IndicatorState.previews.first { it.first == "严重低电量" }.second
        val low = IndicatorState.previews.first { it.first == "低电量" }.second
        val saver = IndicatorState.previews.first { it.first == "省电模式" }.second
        val criticalColor = config.batteryColor(critical, Color.BLACK)
        val lowColor = config.batteryColor(low, Color.BLACK)
        val saverColor = config.batteryColor(saver, Color.BLACK)
        // The three states must not end up in the same colour.
        assertFalse("The critical colour is the low one", criticalColor == lowColor)
        assertFalse("The critical colour is the saver one", criticalColor == saverColor)
        assertFalse("The low colour is the saver one", lowColor == saverColor)
        // The critical one is the deepest: it is drawn darker than the low one.
        assertTrue("The critical colour is not darker than the low one",
            luminance(criticalColor) < luminance(lowColor))
    }

    /** Relative luminance of a colour, the way the contrast floor measures it. */
    private fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    /** The four marks of the band must not be mistaken for each other. */
    @Test fun everyCellularMarkIsItsOwn() {
        val renders = listOf(
            SimPhase.READY to render(demo.copy(wifi = WifiPhase.OFF, networkType = "5G")),
            SimPhase.NO_SIGNAL to render(demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.NO_SIGNAL)),
            SimPhase.EMERGENCY to render(demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.EMERGENCY)),
            SimPhase.ABSENT to render(demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.ABSENT))
        )
        for (i in renders.indices) {
            for (j in i + 1 until renders.size) {
                assertFalse("${renders[i].first} looks like ${renders[j].first}",
                    renders[i].second.sameAs(renders[j].second))
            }
        }
    }

    /** The preview list merges the three broken-SIM states into one entry. */
    @Test fun theBrokenSimStatesShareOnePreview() {
        val names = IndicatorState.previews.map { it.first }
        assertTrue("The merged preview is missing",
            names.any { it.contains("SIM") && it.contains("锁定") })
        assertFalse("The old previews are still there", names.contains("无 SIM 卡"))
        assertFalse("The old previews are still there", names.contains("SIM 停用"))
        assertFalse("The old previews are still there", names.contains("SIM 锁定"))
    }
}
