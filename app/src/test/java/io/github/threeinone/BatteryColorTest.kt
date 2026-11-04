package io.github.threeinone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.BatteryPhase
import io.github.threeinone.model.ChargeProtocol
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.render.IndicatorRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        // No protocol could be read, so the colour says exactly that.
        assertEquals(cfg.unknownColor, cfg.batteryColor(s, Color.WHITE))
    }

    @Test fun chargingColourFollowsTheDetectedProtocol() {
        val cfg = IndicatorConfig(chargeColorAuto = true)
        val charging = IndicatorState.demo.copy(battery = 50, batteryPhase = BatteryPhase.CHARGING)
        // The original OnePlus charger.
        assertEquals(cfg.voocColor, cfg.batteryColor(
            charging.copy(chargeProtocol = ChargeProtocol.SUPER_VOOC), Color.WHITE))
        // A third-party charger speaking the public UFCS standard.
        assertEquals(cfg.ufcsColor, cfg.batteryColor(
            charging.copy(chargeProtocol = ChargeProtocol.UFCS), Color.WHITE))
        // A PPS charger.
        assertEquals(cfg.ppsColor, cfg.batteryColor(
            charging.copy(chargeProtocol = ChargeProtocol.PPS), Color.WHITE))
        // A plain charger, and a fast charger of an unknown rail.
        assertEquals(cfg.slowChargeColor, cfg.batteryColor(
            charging.copy(chargeProtocol = ChargeProtocol.SLOW), Color.WHITE))
        assertEquals(cfg.slowChargeColor, cfg.batteryColor(
            charging.copy(chargeProtocol = ChargeProtocol.FAST), Color.WHITE))
        // A protocol that could not be read at all.
        assertEquals(cfg.unknownColor, cfg.batteryColor(charging, Color.WHITE))
        // Manual mode always wins over the protocol.
        val manual = cfg.copy(chargeColorAuto = false)
        assertEquals(manual.chargingColor, manual.batteryColor(
            charging.copy(chargeProtocol = ChargeProtocol.SUPER_VOOC), Color.WHITE))
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

    @Test fun anOldPaletteMovesOverToTheProtocolTones() {
        // A palette from before the four protocol tones: the green belonged to PPS and
        // the blue to slow charging. The green is now the tone of a third-party UFCS
        // charger and the blue the tone of PPS.
        val old = android.os.Bundle().apply {
            putInt("ppsColor", 0xFF2ECC71.toInt())
            putInt("slowChargeColor", 0xFF3B82F6.toInt())
        }
        val migrated = IndicatorConfig.fromBundle(old)
        assertEquals(0xFF2ECC71.toInt(), migrated.ufcsColor)
        assertEquals(0xFF3B82F6.toInt(), migrated.ppsColor)
        assertEquals(0xFF64748B.toInt(), migrated.slowChargeColor)
        assertEquals(0xFFF97316.toInt(), migrated.unknownColor)
    }

    @Test fun aCustomisedOldPaletteIsLeftAlone() {
        // A colour the user picked does not move to another slot.
        val old = android.os.Bundle().apply {
            putInt("ppsColor", 0xFF123456.toInt())
            putInt("slowChargeColor", 0xFF654321.toInt())
        }
        val migrated = IndicatorConfig.fromBundle(old)
        assertEquals(0xFF123456.toInt(), migrated.ufcsColor)
        assertEquals(0xFF654321.toInt(), migrated.slowChargeColor)
    }

    @Test fun vpnShieldDrawnWhenEnabledAndActive() {
        val on = render(IndicatorState.demo.copy(vpn = true), IndicatorConfig(showVpn = true))
        val off = render(IndicatorState.demo.copy(vpn = true), IndicatorConfig(showVpn = false))
        val onInk = shieldInk(on)
        val offInk = shieldInk(off)
        assertTrue("VPN shield should add ink in the top-left (onInk=$onInk offInk=$offInk)",
            onInk > offInk + 50)
    }

    @Test fun vpnShieldSkippedWhenDisabled() {
        val off = render(IndicatorState.demo.copy(vpn = true), IndicatorConfig(showVpn = false))
        val base = render(IndicatorState.demo.copy(vpn = false), IndicatorConfig(showVpn = true))
        val offInk = shieldInk(off)
        val baseInk = shieldInk(base)
        assertTrue("Shield must not appear when disabled (offInk=$offInk baseInk=$baseInk)",
            offInk <= baseInk + 5)
        assertEquals("No shield ink expected", 0, offInk)
    }

    @Test fun vpnShieldMovesToTheTopRightWhenSwapped() {
        val swapped = render(IndicatorState.demo.copy(vpn = true),
            IndicatorConfig(showVpn = true, swap = true))
        val plain = render(IndicatorState.demo.copy(vpn = true), IndicatorConfig(showVpn = true))
        assertTrue("Swapped shield should be in the top-right",
            swappedInk(swapped) > swappedInk(plain) + 50)
        assertEquals("Swapped shield must leave the top-left empty", 0, shieldInk(swapped))
    }

    private fun render(state: IndicatorState, config: IndicatorConfig): Bitmap {
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        IndicatorRenderer().draw(Canvas(b), 300f, 300f, state, config, Color.WHITE)
        return b
    }

    // The VPN shield lives in the top-left corner (x 12..22, y 4..16 units → 36..66 / 12..48 px).
    private fun shieldInk(b: Bitmap): Int {
        var c = 0
        for (y in 0 until 55) for (x in 28..72) {
            if (Color.alpha(b.getPixel(x, y)) > 0) c++
        }
        return c
    }

    // With swap the shield moves to the top-right corner (x 78..88 units → 234..264 px).
    private fun swappedInk(b: Bitmap): Int {
        var c = 0
        for (y in 0 until 55) for (x in 228..270) {
            if (Color.alpha(b.getPixel(x, y)) > 0) c++
        }
        return c
    }
}
