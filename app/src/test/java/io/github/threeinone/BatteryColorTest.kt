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

    @Test fun criticalThresholdCannotExceedLowThreshold() {
        // Misconfigured (critical > low) must not make low unreachable.
        val cfg = IndicatorConfig(criticalThreshold = 25, lowThreshold = 20)
        val s = IndicatorState.demo.copy(battery = 22, batteryPhase = BatteryPhase.NORMAL)
        assertEquals(low, cfg.batteryColor(s, Color.WHITE))
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
        val lifted = IndicatorConfig.ensureVisible(Color.argb(255, 128, 128, 128))
        assertEquals(255, Color.alpha(lifted))
        assertTrue("Mid-grey must be lifted off the background", lifted != Color.argb(255, 128, 128, 128))
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
