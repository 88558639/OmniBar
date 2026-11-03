package io.github.threeinone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.model.WifiPhase
import io.github.threeinone.render.IndicatorRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TintAlphaTest {
    @Test fun systemTintAlphaIsPreservedAcrossArcWifiTextAndSignalDots() {
        // Use an empty battery arc so the sampled arc pixel is only the faint
        // track (alpha 46) and not the composition of track and progress arc.
        val arcState = IndicatorState.demo.copy(battery = 0)
        val arcConfig = IndicatorConfig(colors = false)
        val opaqueArc = render(arcState, arcConfig, Color.WHITE)
        val translucentArc = render(arcState, arcConfig, Color.argb(128, 255, 255, 255))
        // The battery arc runs across the bottom of the ring, centred on (50, 97).
        assertAlphaNear(opaqueArc, translucentArc, 150, 291)

        val wifiOpaque = render(IndicatorState.demo, IndicatorConfig(), Color.WHITE)
        val wifiTranslucent = render(IndicatorState.demo, IndicatorConfig(), Color.argb(128, 255, 255, 255))
        // The Wi-Fi fan's centre dot sits at (50, 62).
        assertAlphaNear(wifiOpaque, wifiTranslucent, 150, 186)

        val signalOpaque = render(IndicatorState.demo, IndicatorConfig(), Color.WHITE)
        val signalTranslucent = render(IndicatorState.demo, IndicatorConfig(), Color.argb(128, 255, 255, 255))
        // A lit signal dot of the outer ring, at radius 44 and angle -11°.
        assertAlphaNear(signalOpaque, signalTranslucent, 280, 134)

        val textState = IndicatorState.demo.copy(wifi = WifiPhase.OFF)
        val textOpaque = render(textState, IndicatorConfig(), Color.WHITE)
        val textTranslucent = render(textState, IndicatorConfig(), Color.argb(128, 255, 255, 255))
        // The network type text is drawn centred on (50, 57).
        assertRegionAlphaNear(textOpaque, textTranslucent, 110, 145, 190, 200)
    }

    @Test fun opaqueBlackTintDoesNotChangeWhenAlphaIsApplied() {
        val opaque = render(IndicatorState.demo, IndicatorConfig(), Color.BLACK)
        val explicit = render(IndicatorState.demo, IndicatorConfig(), Color.argb(255, 0, 0, 0))
        for (y in 0 until opaque.height) {
            for (x in 0 until opaque.width) {
                assertEquals("Pixel changed at ($x,$y)", opaque.getPixel(x, y), explicit.getPixel(x, y))
            }
        }
    }

    @Test fun localInactiveAlphaMultipliesTintAlpha() {
        val config = IndicatorConfig()
        val state = IndicatorState.demo.copy(wifi = WifiPhase.CONNECTED, wifiLevel = 0)
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f, state, config,
            Color.argb(128, 255, 255, 255))

        // The unlit outermost Wi-Fi band starts at angle 210°, i.e. at (28, 49) units.
        val inactiveBand = Color.alpha(bitmap.getPixel(85, 148))
        assertTrue("Inactive Wi-Fi band must include tint alpha", inactiveBand in 20..35)
    }

    @Test fun customChargingColorStaysOpaqueWhenSystemTintIsTranslucent() {
        val state = IndicatorState.demo.copy(batteryPhase = io.github.threeinone.model.BatteryPhase.CHARGING)
        val config = IndicatorConfig()
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f, state, config,
            Color.argb(128, 255, 255, 255))

        // The charging bolt in the top-right corner keeps the custom charging colour.
        assertEquals(0xff1cb753.toInt(), bitmap.getPixel(267, 10))
    }

    private fun render(state: IndicatorState = IndicatorState.demo,
        config: IndicatorConfig = IndicatorConfig(), tint: Int = Color.BLACK): Bitmap =
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
            IndicatorRenderer().draw(Canvas(it), 300f, 300f, state, config, tint)
        }

    private fun assertAlphaNear(first: Bitmap, second: Bitmap, x: Int, y: Int) {
        val expected = Color.alpha(first.getPixel(x, y))
        val actual = Color.alpha(second.getPixel(x, y))
        assertTrue("Expected a visible reference pixel at ($x,$y)", expected > 0)
        assertTrue("Tint alpha was not preserved at ($x,$y): $expected -> $actual",
            actual in (expected / 2 - 3).coerceAtLeast(0)..(expected / 2 + 3))
    }

    private fun assertRegionAlphaNear(first: Bitmap, second: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int) {
        var bestX = -1
        var bestY = -1
        var expected = 0
        for (y in top..bottom) for (x in left..right) {
            val alpha = Color.alpha(first.getPixel(x, y))
            if (alpha > expected) {
                expected = alpha
                bestX = x
                bestY = y
            }
        }
        assertTrue("Expected a visible text pixel in the selected region", expected > 0)
        val actual = Color.alpha(second.getPixel(bestX, bestY))
        assertTrue("Tint alpha was not preserved at ($bestX,$bestY): $expected -> $actual",
            actual in (expected / 2 - 3).coerceAtLeast(0)..(expected / 2 + 3))
    }
}
