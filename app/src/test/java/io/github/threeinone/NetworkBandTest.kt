package io.github.threeinone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.model.SimPhase
import io.github.threeinone.model.WifiPhase
import io.github.threeinone.render.IndicatorRenderer
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

    private fun render(state: IndicatorState, config: IndicatorConfig = IndicatorConfig()): Bitmap {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f, state, config, Color.BLACK)
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

    private val demo get() = IndicatorState.demo

    // ------------------------------------------------------------------ Wi-Fi markers

    /**
     * The "!" and the "?" used to sit right on the end of the outer arc, so the mark
     * and the fan merged into one blob. They now hang in the top right corner of the
     * fan's box — a region the fan never reaches.
     */
    @Test fun theWifiMarkersDoNotTouchTheFan() {
        val plain = render(demo.copy(wifi = WifiPhase.CONNECTED))
        for (phase in listOf(WifiPhase.NO_INTERNET, WifiPhase.CAPTIVE, WifiPhase.CONNECTING)) {
            val marked = render(demo.copy(wifi = phase))
            // The corner the marker lives in: wide enough for the widest mark ("..."),
            // and it stops above the arc's right shoulder, which reaches up to x = 68.
            // The box is [x0, x1, y0, y1], in units.
            val band = floatArrayOf(
                IndicatorRenderer.WIFI_MARKER_X - 6f, IndicatorRenderer.WIFI_MARKER_X + 6f,
                IndicatorRenderer.WIFI_MARKER_BASELINE - 9f, IndicatorRenderer.WIFI_MARKER_BASELINE + 1f
            )
            assertTrue("$phase: the marker is missing",
                ink(marked, band[0], band[1], band[2], band[3]) > 0)
            // Nothing of the fan may reach into that corner, or the two would overlap.
            assertEquals("$phase: the fan reaches into the marker's corner",
                0, ink(plain, band[0], band[1], band[2], band[3]))
            // The mark may not dip below its baseline either.
            val below = floatArrayOf(
                band[0], band[1], IndicatorRenderer.WIFI_MARKER_BASELINE + 1f, band[3]
            )
            assertEquals("$phase: the marker reaches into the fan",
                0, ink(marked, below[0], below[1], below[2], below[3]))
        }
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
        assertTrue("The placeholder is too narrow: ${box!!.toList()}", box[2] - box[0] >= 28f)
        assertTrue("The placeholder is too thin: ${box.toList()}", box[3] - box[1] >= 4f)
        // It must not look like a network type: a dash pair has no ascender.
        assertTrue("The placeholder is too tall: ${box.toList()}", box[3] - box[1] <= 12f)
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
