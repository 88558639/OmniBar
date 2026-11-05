package io.github.threeinone

import io.github.threeinone.hook.SystemStateSource
import io.github.threeinone.model.ChargeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        chargerVoltageMv: Int = 9000,
        charging: Boolean = true
    ) = SystemStateSource.detectProtocol(
        chargeTech, fastType, ppsMode, chargeTechnology, plugged, fastCharger,
        voltageMv, currentMa, wattage, chargerVoltageMv, charging
    )

    /** A charger that negotiates the UFCS high rail. */
    private val ufcsHigh = 12000

    @Test fun originalChargerIsRecognisedByItsTechnologyLevel() {
        // The charging IC reports the capabilities of the charger: the original
        // OnePlus charger reaches the VOOC level, a plain USB charger stays at 0.
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            detect(chargeTech = 14, chargeTechnology = 3, plugged = 1)
        )
        // The original charger speaks UFCS too — the level is what tells them apart.
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            detect(chargeTech = 14, chargeTechnology = 3, plugged = 1, wattage = 120)
        )
    }

    @Test fun thirdPartyUfcsChargerIsNotTheProprietaryRail() {
        // A third-party charger that speaks UFCS reports a lower technology level, so
        // it must not claim the violet SuperVOOC colour.
        assertEquals(
            ChargeProtocol.UFCS,
            detect(chargeTech = 14, chargeTechnology = 0, plugged = 1, wattage = 30,
                voltageMv = 12000, chargerVoltageMv = 12000)
        )
    }

    @Test fun aPpsChargerIsRecognisedByItsNegotiatedVoltage() {
        // PPS negotiates in 9 V steps and stays below the UFCS rail. The firmware
        // reports the same charge type for both, so the voltage separates them.
        assertEquals(
            ChargeProtocol.PPS,
            detect(chargeTech = 14, chargeTechnology = 0, plugged = 1, wattage = 18,
                voltageMv = 9000, chargerVoltageMv = 9000)
        )
    }

    @Test fun aPlainUsbPortIsNotAFastCharger() {
        // The firmware reports the UFCS charge type even for a plain USB port — a power
        // strip's port for instance. Only the voltage the charger actually delivers
        // tells them apart: a fast charger pushes the rail to 9 V, a plain port stays
        // at 5 V. A plain port is slow charging, not an unknown protocol.
        assertEquals(
            ChargeProtocol.SLOW,
            detect(chargeTech = 14, chargeTechnology = 0, plugged = 1, wattage = 0,
                voltageMv = 5000, chargerVoltageMv = 4648)
        )
    }

    @Test fun ufcsIsNotMistakenForPpsEvenWhenPpsFlagIsSet() {
        // The regression: `pps_chg_mode` is non-zero while UFCS runs, so the charge
        // technology has to be checked before it.
        assertEquals(
            ChargeProtocol.UFCS,
            detect(chargeTech = 14, ppsMode = 4, fastCharger = true, plugged = 1,
                voltageMv = 12000, chargerVoltageMv = 12000)
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
        // The technology level of the original charger.
        assertEquals(ChargeProtocol.SUPER_VOOC, detect(chargeTechnology = 3))
        // Plain USB charger.
        assertEquals(ChargeProtocol.SLOW, detect(plugged = 1))
    }

    @Test fun negotiatedVoltageStillAllowsARoughGuess() {
        // 11 V is the proprietary SuperVOOC rail, PPS tops out around 9 V.
        assertEquals(ChargeProtocol.SUPER_VOOC, detect(voltageMv = 11000))
        assertEquals(ChargeProtocol.PPS, detect(voltageMv = 9000, currentMa = 5000, chargeTech = 4,
            chargerVoltageMv = 9000))
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
            ChargeProtocol.UFCS,
            detect(chargeTech = 14, plugged = 1, wattage = 0, voltageMv = 12000,
                chargerVoltageMv = 12000, charging = true)
        )
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            detect(chargeTech = 14, chargeTechnology = 3, plugged = 1, wattage = 0, charging = true)
        )
    }

    /**
     * The contract's power ceiling is the value the 电池检测 app shows as "PPS 最大功率".
     * The node carries it in an unspecified unit, so the raw values are normalised the
     * same way the app does.
     */
    @Test fun theContractPowerCeilingIsNormalisedToWatts() {
        assertEquals(120, SystemStateSource.contractWatts("120"))
        assertEquals(120, SystemStateSource.contractWatts("120000"))
        assertEquals(120, SystemStateSource.contractWatts("120000000"))
        assertEquals(30, SystemStateSource.contractWatts("30"))
        assertEquals(30, SystemStateSource.contractWatts("30000"))
        assertEquals(0, SystemStateSource.contractWatts(null))
        assertEquals(0, SystemStateSource.contractWatts(""))
        assertEquals(0, SystemStateSource.contractWatts("n/a"))
    }

    /**
     * The original OnePlus charger speaks UFCS and PPS as well, and the firmware only
     * flags the proprietary SuperVOOC rail when the original cable is used. The 120 W
     * contract ceiling identifies it regardless of the link that was negotiated.
     */
    @Test fun theOriginalChargerIsRecognisedByIts120WContractCeiling() {
        // UFCS link, but the original 120 W unit → the proprietary rail (violet).
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            SystemStateSource.fastProtocolFromFlags(voocIng = 0, ppsIng = 4, contractWatts = 120)
        )
        // PPS link, same charger → still the proprietary rail.
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            SystemStateSource.fastProtocolFromFlags(voocIng = 0, ppsIng = 2, contractWatts = 120)
        )
        // A third-party UFCS charger signs a much lower contract → stays green.
        assertEquals(
            ChargeProtocol.UFCS,
            SystemStateSource.fastProtocolFromFlags(voocIng = 0, ppsIng = 4, contractWatts = 30)
        )
        // A third-party PPS charger → stays blue.
        assertEquals(
            ChargeProtocol.PPS,
            SystemStateSource.fastProtocolFromFlags(voocIng = 0, ppsIng = 2, contractWatts = 30)
        )
        // The proprietary flag always wins, whatever the ceiling says.
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            SystemStateSource.fastProtocolFromFlags(voocIng = 1, ppsIng = 4, contractWatts = 30)
        )
        // No fast-charge standard running at all.
        assertNull(
            SystemStateSource.fastProtocolFromFlags(voocIng = 0, ppsIng = 0, contractWatts = 120)
        )
    }
}
