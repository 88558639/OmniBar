package io.github.threeinone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.BatteryPhase
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.render.IndicatorRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatteryColorTest {
    private val charging = 0xFF1CB753.toInt()
    private val low = 0xFFE7433A.toInt()
    private val critical = 0xFFD50000.toInt()

    @Test fun criticalTakesPriorityBelowCriticalThreshold() {
        val cfg = IndicatorConfig(criticalThreshold = 10, lowThreshold = 20)
        val s = IndicatorState.demo.copy(battery = 5, batteryPhase = BatteryPhase.NORMAL)
        assertEquals(critical, cfg.batteryColor(s, Color.WHITE))
    }

    @Test fun lowAppliesBetweenCriticalAndLowThreshold() {
        val cfg = IndicatorConfig(criticalThreshold = 10, lowThreshold = 20)
        val s = IndicatorState.demo.copy(battery = 15, batteryPhase = BatteryPhase.NORMAL)
        assertEquals(low, cfg.batteryColor(s, Color.WHITE))
    }

    @Test fun normalBatteryUsesTint() {
        val cfg = IndicatorConfig()
        val s = IndicatorState.demo.copy(battery = 60, batteryPhase = BatteryPhase.NORMAL)
        assertEquals(Color.WHITE, cfg.batteryColor(s, Color.WHITE))
    }

    @Test fun chargingUsesChargingColorRegardlessOfLevel() {
        val cfg = IndicatorConfig()
        val s = IndicatorState.demo.copy(battery = 5, batteryPhase = BatteryPhase.CHARGING)
        assertEquals(charging, cfg.batteryColor(s, Color.WHITE))
    }

    @Test fun criticalBandSitsBelowLowBand() {
        val cfg = IndicatorConfig(criticalThreshold = 10, lowThreshold = 20)
        val crit = IndicatorState.demo.copy(battery = 8, batteryPhase = BatteryPhase.NORMAL)
        val lowS = IndicatorState.demo.copy(battery = 15, batteryPhase = BatteryPhase.NORMAL)
        val normal = IndicatorState.demo.copy(battery = 25, batteryPhase = BatteryPhase.NORMAL)
        assertEquals(critical, cfg.batteryColor(crit, Color.WHITE))
        assertEquals(low, cfg.batteryColor(lowS, Color.WHITE))
        assertEquals(Color.WHITE, cfg.batteryColor(normal, Color.WHITE))
    }

    @Test fun criticalThresholdCappedWhenAboveLow() {
        // Misconfiguration (critical > low) must not crash and stays deterministic.
        val cfg = IndicatorConfig(criticalThreshold = 25, lowThreshold = 20)
        val below = IndicatorState.demo.copy(battery = 5, batteryPhase = BatteryPhase.NORMAL)
        val above = IndicatorState.demo.copy(battery = 22, batteryPhase = BatteryPhase.NORMAL)
        assertEquals(critical, cfg.batteryColor(below, Color.WHITE))
        assertEquals(Color.WHITE, cfg.batteryColor(above, Color.WHITE))
    }

    @Test fun colorsDisabledFallsBackToTint() {
        val cfg = IndicatorConfig(colors = false)
        val s = IndicatorState.demo.copy(battery = 5, batteryPhase = BatteryPhase.NORMAL)
        assertEquals(Color.WHITE, cfg.batteryColor(s, Color.WHITE))
    }

    @Test fun ensureVisibleKeepsHighContrastColorUnchanged() {
        assertEquals(critical, IndicatorConfig.ensureVisible(critical))
    }

    @Test fun ensureVisibleLiftsNearInvisibleColor() {
        // Light grey ~0.78 is nearly indistinguishable from the 0.78 reference background;
        // ensureVisible must mix it toward black/white until it clears the 1.2:1 floor.
        val nearInvisible = Color.argb(255, 198, 198, 198)
        val lifted = IndicatorConfig.ensureVisible(nearInvisible)
        assertEquals(255, Color.alpha(lifted))
        assertTrue("Near-reference grey must be lifted off the background", lifted != nearInvisible)
    }

    @Test fun vpnShieldDrawnWhenEnabledAndActive() {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f,
            IndicatorState.demo.copy(vpn = true), IndicatorConfig(showVpn = true), Color.WHITE)
        assertTrue("VPN shield should be drawn", hasInk(bitmap, 246, 270, 48, 72))
    }

    @Test fun vpnShieldSkippedWhenDisabled() {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(bitmap), 300f, 300f,
            IndicatorState.demo.copy(vpn = true), IndicatorConfig(showVpn = false), Color.WHITE)
        assertFalse("VPN shield must be skipped when disabled", hasInk(bitmap, 246, 270, 48, 72))
    }

    private fun hasInk(bitmap: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        for (y in y0..y1 step 3) for (x in x0..x1 step 3) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 0) return true
        }
        return false
    }
}
