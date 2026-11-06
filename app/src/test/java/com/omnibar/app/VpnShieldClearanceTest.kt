package com.omnibar.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.omnibar.app.config.IndicatorConfig
import com.omnibar.app.model.BatteryPhase
import com.omnibar.app.model.IndicatorState
import com.omnibar.app.render.IndicatorRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The VPN shield must not run into the signal dots.
 *
 * The shield used to be pinned to the percentage only. Its pointed bottom reached into
 * the top left signal dot and the two marks merged into one blob on the status bar.
 *
 * The assertions work on the rendered pixels instead of on the constants, so they
 * describe what the eye sees: the ink of the shield and the ink of the dots are two
 * separate shapes with a visible gap between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VpnShieldClearanceTest {

    /** Pixels per indicator unit — the bitmap is 300 px for the 100-unit canvas. */
    private val px = 3f
    private val side = 300

    /**
     * A charging battery, so the percentage is painted in the charging colour while the
     * shield keeps the plain tint. The difference between the two renders is then exactly
     * the shield — even where it covers the digits.
     */
    private val charging = IndicatorState.demo.copy(batteryPhase = BatteryPhase.CHARGING)

    private fun render(state: IndicatorState, config: IndicatorConfig = IndicatorConfig()): Bitmap =
        Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also {
            IndicatorRenderer().draw(Canvas(it), side.toFloat(), side.toFloat(), state, config, Color.BLACK)
        }

    /** Every pixel that carries ink, keyed by "y * side + x". */
    private fun ink(b: Bitmap): Set<Int> {
        val out = HashSet<Int>()
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (Color.alpha(b.getPixel(x, y)) > 32) out.add(y * side + x)
            }
        }
        return out
    }

    private fun xUnits(key: Int) = (key % side) / px

    private fun yUnits(key: Int) = (key / side) / px

    private fun nearest(a: Set<Int>, b: Set<Int>): Float {
        var best = Float.MAX_VALUE
        for (p in a) {
            val ax = (p % side).toFloat()
            val ay = (p / side).toFloat()
            for (q in b) {
                val dx = ax - (q % side)
                val dy = ay - (q / side)
                val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (d < best) best = d
            }
        }
        return best / px
    }

    /** The shield on its own: the difference between the two renders is the badge. */
    private fun shieldInk(config: IndicatorConfig = IndicatorConfig(), right: Boolean = false): Set<Int> {
        val with = ink(render(charging.copy(vpn = true), config))
        val without = ink(render(charging.copy(vpn = false), config))
        return (with - without).filter { if (right) xUnits(it) > 55f else xUnits(it) < 45f }.toSet()
    }

    /** One column of the signal dots — the other one is on the far side of the canvas. */
    private fun dotInk(config: IndicatorConfig = IndicatorConfig(), right: Boolean = false): Set<Int> {
        val state = IndicatorState.demo.copy(vpn = false, airplane = false)
        return ink(render(state, config))
            .filter { if (right) xUnits(it) > 75f else xUnits(it) < 25f }
            .filter { yUnits(it) < 60f }
            .toSet()
    }

    /** The percentage — the digits hang from the top of the indicator. */
    private fun digitInk(config: IndicatorConfig = IndicatorConfig()): Set<Int> =
        ink(render(charging.copy(vpn = false), config))
            .filter { yUnits(it) < 60f && xUnits(it) in 20f..80f }
            .toSet()

    @Test fun theShieldKeepsItsDistanceFromTheSignalDots() {
        val shield = shieldInk()
        val dots = dotInk()
        assertTrue("The VPN shield must be rendered", shield.isNotEmpty())
        assertTrue("The signal dots must be rendered", dots.isNotEmpty())

        val overlap = shield.count { dots.contains(it) }
        assertEquals("The VPN shield overlaps the signal dots ($overlap pixels)", 0, overlap)

        // A hair of antialiasing may touch, but the two marks must not run together:
        // roughly two units (1.6 px on the device) of daylight in between.
        val gap = nearest(shield, dots)
        assertTrue("The VPN shield and the signal dots are only $gap units apart", gap >= 2f)
    }

    @Test fun theShieldStaysAboveTheSignalDots() {
        val shield = shieldInk()
        val dots = dotInk()
        val shieldBottom = shield.maxOf { yUnits(it) }
        val dotsTop = dots.minOf { yUnits(it) }
        assertTrue(
            "The shield reaches down to $shieldBottom units, the dots start at $dotsTop",
            shieldBottom < dotsTop
        )
    }

    @Test fun theShieldStaysInsideTheCanvas() {
        val shield = shieldInk()
        assertTrue(
            "The shield must not be cut off at the top of the canvas",
            shield.minOf { yUnits(it) } >= 0f
        )
        assertTrue(
            "The shield must not be cut off at the left of the canvas",
            shield.minOf { xUnits(it) } >= 0f
        )
    }

    @Test fun theShieldDoesNotTouchThePercentage() {
        // The percentage is the only other mark in the top band; the badge may stand
        // next to it, but it must not grow into it.
        val shield = shieldInk()
        val digits = digitInk()
        assertTrue("The percentage must be rendered", digits.isNotEmpty())
        val overlap = shield.count { digits.contains(it) }
        assertEquals("The VPN shield overlaps the percentage ($overlap pixels)", 0, overlap)
    }

    @Test fun theMirroredShieldKeepsItsDistanceFromTheSignalDots() {
        val config = IndicatorConfig(swap = true)
        val shield = shieldInk(config, right = true)
        val dots = dotInk(config, right = true)
        assertTrue("The VPN shield must be rendered", shield.isNotEmpty())
        val overlap = shield.count { dots.contains(it) }
        assertEquals("The VPN shield overlaps the signal dots ($overlap pixels)", 0, overlap)
        assertTrue(
            "The mirrored shield must not reach into the digits",
            shield.minOf { xUnits(it) } >= 55f
        )
    }
}
