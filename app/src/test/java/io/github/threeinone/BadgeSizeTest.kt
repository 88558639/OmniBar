package io.github.threeinone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.model.WifiPhase
import io.github.threeinone.render.IndicatorRenderer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two badges that were too small to read at status bar size.
 *
 * Both are measured in indicator units (the canvas is 100 units wide) on the rendered
 * pixels, so the assertions describe what the eye sees rather than a constant in the
 * source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BadgeSizeTest {

    /** Pixels per indicator unit — the bitmap is 300 px for the 100-unit canvas. */
    private val px = 3f

    private fun render(state: IndicatorState, config: IndicatorConfig = IndicatorConfig()): Bitmap =
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
            IndicatorRenderer().draw(Canvas(it), 300f, 300f, state, config, Color.BLACK)
        }

    /** Ink box inside the given window, in indicator units. */
    private fun ink(b: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): RectF? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in (y0 * px).toInt()..(y1 * px).toInt()) {
            for (x in (x0 * px).toInt()..(x1 * px).toInt()) {
                if (Color.alpha(b.getPixel(x, y)) > 32) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) null else RectF(minX / px, minY / px, maxX / px, maxY / px)
    }

    @Test fun theFlightModePlaneIsAtLeastAsBigAsTheNetworkLabel() {
        // Both live in the same band: the plane replaces the fan, the label replaces it
        // too, so the eye compares them directly.
        val label = ink(render(IndicatorState.demo.copy(wifi = WifiPhase.OFF)), 22f, 78f, 34f, 82f)
        val plane = ink(
            render(IndicatorState.demo.copy(wifi = WifiPhase.OFF, airplane = true)),
            22f, 78f, 34f, 82f
        )
        assertNotNull("The 5G label must be rendered", label)
        assertNotNull("The flight-mode plane must be rendered", plane)
        val labelHeight = label!!.height()
        val planeHeight = plane!!.height()
        assertTrue(
            "The plane ($planeHeight units) must not be smaller than the 5G label ($labelHeight units)",
            planeHeight >= labelHeight
        )
    }

    @Test fun theVpnShieldIsBigEnoughToRead() {
        val shield = ink(render(IndicatorState.demo.copy(vpn = true)), 0f, 20f, 0f, 30f)
        assertNotNull("The VPN shield must be rendered", shield)
        assertTrue(
            "The VPN shield is only ${shield!!.height()} units tall",
            shield.height() >= 15f
        )
    }
}
