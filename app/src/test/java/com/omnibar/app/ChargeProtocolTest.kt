package com.omnibar.app

import com.omnibar.app.hook.SystemStateSource
import com.omnibar.app.model.ChargeProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Protocol classification rules.
 *
 * The colour follows the text the 电池检测 app shows as "快充模式" — never a guess
 * from instantaneous power or hardware. The violet rail is the proprietary SuperVOOC
 * (only the original charger negotiates it); UFCS and PPS are the public standards
 * third-party chargers speak; PD/DCP is blue; plain/slow is orange.
 *
 * The single wattage that is read is the *contract ceiling* (`ppschg_power`), and only
 * to split the two UFCS tiers: the original 120 W unit signs a 120 W contract (the
 * "快充模式" text reads `UFCS(120.00W)`), a third-party UFCS charger signs far less.
 * This is what fixes the 酷态科 10 号 Ultra and 公牛 PD20W miscolour bugs.
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

    // ------------------------------------------------------------------ named protocols

    /** The proprietary rail is named by its charge-type code, nothing else. */
    @Test fun superVoocChargeTypeIsRecognised() {
        assertEquals(ChargeProtocol.SUPER_VOOC, detect(chargeTech = 3))
    }

    /** UFCS is named by its charge-type code. */
    @Test fun ufcsChargeTypeIsRecognised() {
        assertEquals(ChargeProtocol.UFCS, detect(chargeTech = 6))
    }

    /** PPS is named by its charge-type code. */
    @Test fun ppsChargeTypeIsRecognised() {
        assertEquals(ChargeProtocol.PPS, detect(chargeTech = 4))
    }

    /** The broadcast `fast_chg_type` carries the same codes on some builds. */
    @Test fun theBroadcastFastTypeCarriesTheSameCodes() {
        assertEquals(ChargeProtocol.SUPER_VOOC, detect(fastType = 3))
        assertEquals(ChargeProtocol.UFCS, detect(fastType = 6))
        assertEquals(ChargeProtocol.PPS, detect(fastType = 4))
    }

    // ------------------------------------------------------------------ the two bugs

    /**
     * The 公牛 PD20W bug: a PD charger must stay blue, never flip to violet or UFCS.
     * A PD charger reports no proprietary charge type, so it lands on the plain
     * USB-charger fallback.
     */
    @Test fun aPdChargerStaysPlain() {
        assertEquals(ChargeProtocol.SLOW, detect(plugged = 1))
    }

    /**
     * The 酷态科 10 号 Ultra bug: a high-power third-party UFCS charger must never
     * claim the violet rail. The contract ceiling alone must not promote it — a
     * third-party UFCS charger is green, whatever its wattage.
     */
    @Test fun aThirdPartyUfcsChargerIsNotTheProprietaryRail() {
        // No proprietary charge type, no proprietary broadcast flag → not violet.
        assertEquals(ChargeProtocol.UFCS, detect(chargeTech = 6))
        assertEquals(ChargeProtocol.UFCS, detect(chargeTech = 6, wattage = 120))
    }

    // ------------------------------------------------------------------ fallbacks

    /** A fast charger the firmware flagged but named no rail for. */
    @Test fun anUnnamedFastChargerIsFast() {
        assertEquals(ChargeProtocol.FAST, detect(fastCharger = true))
    }

    /** Plugged in but no fast protocol reported: a plain USB charger. */
    @Test fun aPlainUsbChargerIsSlow() {
        assertEquals(ChargeProtocol.SLOW, detect(plugged = 1))
    }

    /** Nothing known at all. */
    @Test fun nothingKnownMeansNoProtocol() {
        assertEquals(ChargeProtocol.NONE, detect(wattage = 0))
    }

    /** An idle charger negotiates nothing, so the colour stays neutral. */
    @Test fun aChargerThatIsPluggedInButIdleIsNotFastCharging() {
        assertEquals(
            ChargeProtocol.NONE,
            detect(chargeTech = 6, plugged = 1, fastCharger = true, charging = false)
        )
    }

    // ------------------------------------------------------------------ contract tier

    /**
     * The contract ceiling is the number the 电池检测 app prints inside the "快充模式"
     * text as `UFCS(120.00W)`. It is normalised the same way the app does.
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
     * The proprietary flag always wins — it is the "快充模式 == SVOOC" the user sees,
     * and only the original charger negotiates it, whatever the contract says.
     */
    @Test fun theProprietaryFlagAlwaysWins() {
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            SystemStateSource.classifyFastCharging(voocIng = 1, ppsIng = 4, contractWatts = 30)
        )
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            SystemStateSource.classifyFastCharging(voocIng = 1, ppsIng = 0, contractWatts = 0)
        )
    }

    /**
     * The original charger's UFCS handshake reads `UFCS(120.00W)` — its 120 W contract
     * is the "百瓦档位" marker. It keeps the violet rail even though the link is UFCS.
     */
    @Test fun theOriginalUfcsHandshakeKeepsTheVioletRail() {
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            SystemStateSource.classifyFastCharging(voocIng = 0, ppsIng = 4, contractWatts = 120)
        )
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            SystemStateSource.classifyFastCharging(voocIng = 0, ppsIng = 4, contractWatts = 100)
        )
    }

    /**
     * The 酷态科 10 号 Ultra bug: a third-party UFCS charger signs far less than the
     * original's 120 W contract, so it must stay green — never violet, no matter how
     * high its own ceiling climbs below the 100 W tier.
     */
    @Test fun aThirdPartyUfcsChargerStaysGreen() {
        assertEquals(
            ChargeProtocol.UFCS,
            SystemStateSource.classifyFastCharging(voocIng = 0, ppsIng = 4, contractWatts = 30)
        )
        assertEquals(
            ChargeProtocol.UFCS,
            SystemStateSource.classifyFastCharging(voocIng = 0, ppsIng = 4, contractWatts = 65)
        )
        assertEquals(
            ChargeProtocol.UFCS,
            SystemStateSource.classifyFastCharging(voocIng = 0, ppsIng = 4, contractWatts = 99)
        )
    }

    /**
     * A third-party PPS charger is blue. The 公牛 PD20W bug: a PPS charger must stay
     * in its own tier and never jump to violet.
     */
    @Test fun aThirdPartyPpsChargerStaysPps() {
        assertEquals(
            ChargeProtocol.PPS,
            SystemStateSource.classifyFastCharging(voocIng = 0, ppsIng = 2, contractWatts = 30)
        )
        assertEquals(
            ChargeProtocol.PPS,
            SystemStateSource.classifyFastCharging(voocIng = 0, ppsIng = 2, contractWatts = 120)
        )
    }

    /** No fast-charge standard running at all. */
    @Test fun noFastChargeStandardYieldsNothing() {
        assertEquals(
            null,
            SystemStateSource.classifyFastCharging(voocIng = 0, ppsIng = 0, contractWatts = 120)
        )
    }

    /** `14` is shared by UFCS and PPS; the IC flag and active USB port disambiguate it. */
    @Test fun sharedCode14KeepsUfcsAndPpsApart() {
        assertEquals(
            ChargeProtocol.UFCS,
            SystemStateSource.classifyChargeTechnology(
                tech = "14", ppsIng = 3, usbActive = "C", ufcsTier = ChargeProtocol.UFCS
            )
        )
        assertEquals(
            ChargeProtocol.PPS,
            SystemStateSource.classifyChargeTechnology(
                tech = "14", ppsIng = 2, usbActive = "PD_PPS", ufcsTier = ChargeProtocol.UFCS
            )
        )
        assertEquals(
            ChargeProtocol.PPS,
            SystemStateSource.classifyChargeTechnology(
                tech = "14", ppsIng = 0, usbActive = "PD_PPS", ufcsTier = ChargeProtocol.UFCS
            )
        )
        assertEquals(
            null,
            SystemStateSource.classifyChargeTechnology(
                tech = "14", ppsIng = 0, usbActive = "C", ufcsTier = ChargeProtocol.UFCS
            )
        )
        assertEquals(
            ChargeProtocol.PPS,
            SystemStateSource.classifyChargeTechnology(
                tech = "PPS", ppsIng = 3, usbActive = "C", ufcsTier = ChargeProtocol.UFCS
            )
        )
    }

    /** A plain PD charger has no Oplus private flags; standard power_supply nodes decide. */
    @Test fun plainPdChargerIsBlue() {
        assertEquals(
            ChargeProtocol.PD,
            SystemStateSource.classifyUsbSupply("UNKNOWN", "USB_PD", fastCharge = false)
        )
        assertEquals(
            ChargeProtocol.PD,
            SystemStateSource.classifyUsbSupply("PD", "USB_PD", fastCharge = false)
        )
        assertEquals(
            ChargeProtocol.PD,
            SystemStateSource.classifyUsbSupply("DCP", "USB", fastCharge = false)
        )
        assertEquals(
            ChargeProtocol.PPS,
            SystemStateSource.classifyUsbSupply("PD_PPS", "USB_PD", fastCharge = false)
        )
        assertEquals(
            null,
            SystemStateSource.classifyUsbSupply("UNKNOWN", "", fastCharge = false)
        )
    }

    /** The original SVOOC charger is a DCP port with the fast-charge rail still on. */
    @Test fun originalSvoocChargerIsViolet() {
        assertEquals(
            ChargeProtocol.SUPER_VOOC,
            SystemStateSource.classifyUsbSupply("DCP", "USB_DCP", fastCharge = true)
        )
        assertEquals(
            ChargeProtocol.PD,
            SystemStateSource.classifyUsbSupply("DCP", "USB_DCP", fastCharge = false)
        )
        assertEquals(
            ChargeProtocol.PD,
            SystemStateSource.classifyUsbSupply("PD", "USB_PD", fastCharge = true)
        )
    }
}
