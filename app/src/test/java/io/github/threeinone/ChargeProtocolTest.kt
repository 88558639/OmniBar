package io.github.threeinone

import io.github.threeinone.hook.SystemStateSource
import io.github.threeinone.model.ChargeProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Protocol detection rules.
 *
 * The firmware reports the negotiated charge technology as `usb_fast_chg_type`; the
 * Oplus broadcast extras are a fallback. The order matters: `pps_chg_mode` is also set
 * while UFCS is running, so consulting it first made every UFCS charger look like a
 * plain PPS charger and show up in the green colour instead of the violet one.
 */
class ChargeProtocolTest {
    /** All telemetry zero — nothing known yet. */
    private fun detect(
        chargeTech: Int = 0,
        fastType: Int = 0,
        ppsMode: Int = 0,
        chargeTechnology: Int = 0,
        plugged: Int = 0,
        fastCharger: Boolean = false,
        voltageMv: Int = 0,
        currentMa: Int = 0,
        wattage: Int = 20,
        charging: Boolean = true
    ) = SystemStateSource.detectProtocol(
        chargeTech, fastType, ppsMode, chargeTechnology, plugged, fastCharger,
        voltageMv, currentMa, wattage, charging
    )

    @Test fun originalChargerIsRecognisedByItsPower() {
        // The original OnePlus charger reports UFCS as the charge technology but
        // negotiates 100 W and above — that is what identifies the proprietary rail.
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            detect(chargeTech = 14, plugged = 1, wattage = 120)
        )
    }

    @Test fun thirdPartyUfcsChargerIsNotTheProprietaryRail() {
        // A third-party charger that speaks UFCS negotiates far less power, so it must
        // not claim the violet SuperVOOC colour.
        assertEquals(
            ChargeProtocol.FAST,
            detect(chargeTech = 14, plugged = 1, wattage = 30)
        )
        assertEquals(
            ChargeProtocol.FAST,
            detect(chargeTech = 14, ppsMode = 4, plugged = 1, wattage = 20)
        )
    }

    @Test fun ufcsIsNotMistakenForPpsEvenWhenPpsFlagIsSet() {
        // The regression: `pps_chg_mode` is non-zero while UFCS runs, so the charge
        // technology has to be checked before it.
        assertEquals(
            ChargeProtocol.FAST,
            detect(chargeTech = 14, ppsMode = 4, fastCharger = true, plugged = 1)
        )
    }

    @Test fun superVoocChargeTypeIsRecognised() {
        assertEquals(ChargeProtocol.SUPER_VOOC, detect(chargeTech = 3))
    }

    @Test fun ppsChargeTechnologyIsRecognised() {
        assertEquals(ChargeProtocol.PPS, detect(chargeTech = 4))
    }

    @Test fun unknownChargeTechnologyFallsBackToTheOplusExtras() {
        // The broadcast extra `fast_chg_type` still identifies the proprietary rail.
        assertEquals(ChargeProtocol.SUPER_VOOC, detect(fastType = 4))
        // A fast charger without a named rail.
        assertEquals(ChargeProtocol.FAST, detect(fastCharger = true))
        assertEquals(ChargeProtocol.FAST, detect(chargeTechnology = 3))
        // Plain USB charger.
        assertEquals(ChargeProtocol.SLOW, detect(plugged = 1))
    }

    @Test fun negotiatedVoltageStillAllowsARoughGuess() {
        // 11 V is the proprietary SuperVOOC rail, PPS tops out around 9 V.
        assertEquals(ChargeProtocol.SUPER_VOOC, detect(voltageMv = 11000))
        assertEquals(ChargeProtocol.PPS, detect(voltageMv = 9000, currentMa = 5000))
        assertEquals(ChargeProtocol.FAST, detect(voltageMv = 9000))
        assertEquals(ChargeProtocol.SLOW, detect(voltageMv = 5000))
    }

    @Test fun nothingKnownMeansNoProtocol() {
        assertEquals(ChargeProtocol.NONE, detect(wattage = 0))
    }

    @Test fun aChargerThatIsPluggedInButIdleIsNotFastCharging() {
        // The charge technology keeps its last value while the charger sits idle, so
        // a plugged-in USB port that is not charging must not look like UFCS.
        assertEquals(
            ChargeProtocol.NONE,
            detect(chargeTech = 14, plugged = 1, fastCharger = true, charging = false)
        )
    }

    @Test fun aFullBatteryStillCountsAsCharging() {
        // A full battery draws no power, so the live reading drops to zero even on the
        // original charger — the charging state is what keeps the colour.
        assertEquals(
            ChargeProtocol.FAST,
            detect(chargeTech = 14, plugged = 1, wattage = 0, charging = true)
        )
    }
}
