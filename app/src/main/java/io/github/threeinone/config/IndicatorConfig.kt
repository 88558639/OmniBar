package io.github.threeinone.config

import android.content.SharedPreferences
import io.github.threeinone.model.BatteryPhase
import io.github.threeinone.model.ChargeProtocol
import io.github.threeinone.model.IndicatorState

data class IndicatorConfig(
    val enabled: Boolean = false,
    val lockscreen: Boolean = true,
    val size: Int = 22,
    val wifiScale: Int = 115,
    val textScale: Int = 150,
    val stroke: Int = 120,
    val dotScale: Int = 100,
    val dotSpacing: Int = 100,
    val horizontalPadding: Int = 2,
    val verticalOffset: Int = 0,
    val colors: Boolean = true,
    val chargingColor: Int = 0xFF1CB753.toInt(),
    val lowColor: Int = 0xFFE7433A.toInt(),
    val criticalColor: Int = 0xFFD50000.toInt(),
    val saverColor: Int = 0xFFFFB300.toInt(),
    val lowThreshold: Int = 20,
    val criticalThreshold: Int = 10,
    val showVpn: Boolean = true,
    // --- SuperIcon port: dual-SIM + traffic activity + reverse layout (all default off) ---
    val swap: Boolean = false,
    val roamingColor: Int = 0xFF2F80ED.toInt(),
    val dualSim: Boolean = true,
    val showActivity: Boolean = false,
    val showNumber: Boolean = false,
    val numberScale: Int = 115,
    val autoNumber: Boolean = false,
    val numberThreshold: Int = 20,
    // --- charging protocol palette (original charger / UFCS / PPS / plain / unknown) ---
    val chargeColorAuto: Boolean = true,
    val voocColor: Int = 0xFF9C6BFF.toInt(),
    val ufcsColor: Int = 0xFF2ECC71.toInt(),
    val ppsColor: Int = 0xFF3B82F6.toInt(),
    val slowChargeColor: Int = 0xFF64748B.toInt(),
    val unknownColor: Int = 0xFFF97316.toInt()
) {
    fun showsNumber(state: IndicatorState): Boolean =
        state.batteryPhase != BatteryPhase.CHARGING && state.batteryPhase != BatteryPhase.FULL && (showNumber ||
        (autoNumber &&
            state.battery in 0..numberThreshold.coerceIn(1, 100)))

    fun batteryColor(state: IndicatorState, tint: Int): Int {
        if (!colors || state.battery < 0) return tint
        val critCut = criticalThreshold.coerceAtMost(lowThreshold)
        return when {
            state.batteryPhase in setOf(BatteryPhase.CHARGING, BatteryPhase.FULL) ->
                ensureVisible(chargeColor(state))
            state.battery in 0..critCut -> ensureVisible(criticalColor)
            state.battery in 0..lowThreshold -> ensureVisible(lowColor)
            state.powerSave -> ensureVisible(saverColor)
            else -> tint
        }
    }

    /**
     * Colour used while charging.
     *
     * In automatic mode the detected protocol decides: the original OnePlus charger
     * gets the violet tone, a third-party charger speaking the public UFCS standard the
     * green one, a PPS charger the blue one, and a charger whose protocol could not be
     * read at all the orange one.
     */
    fun chargeColor(state: IndicatorState): Int = when {
        !chargeColorAuto -> chargingColor
        state.chargeProtocol == ChargeProtocol.SUPER_VOOC -> voocColor
        state.chargeProtocol == ChargeProtocol.UFCS -> ufcsColor
        state.chargeProtocol == ChargeProtocol.PPS -> ppsColor
        state.chargeProtocol == ChargeProtocol.NONE -> unknownColor
        else -> slowChargeColor
    }

    /** Serialise for the SystemUI process (cross-process settings bridge). */
    fun toBundle(): android.os.Bundle = android.os.Bundle().apply {
        putBoolean("enabled", enabled)
        putBoolean("lockscreen", lockscreen)
        putInt("size", size)
        putInt("wifiScale", wifiScale)
        putInt("textScale", textScale)
        putInt("stroke", stroke)
        putInt("dotScale", dotScale)
        putInt("dotSpacing", dotSpacing)
        putInt("horizontalPadding", horizontalPadding)
        putInt("verticalOffset", verticalOffset)
        putBoolean("colors", colors)
        putInt("chargingColor", chargingColor)
        putInt("lowColor", lowColor)
        putInt("criticalColor", criticalColor)
        putInt("saverColor", saverColor)
        putInt("lowThreshold", lowThreshold)
        putInt("criticalThreshold", criticalThreshold)
        putBoolean("showVpn", showVpn)
        putBoolean("swap", swap)
        putInt("roamingColor", roamingColor)
        putBoolean("dualSim", dualSim)
        putBoolean("showActivity", showActivity)
        putBoolean("showNumber", showNumber)
        putInt("numberScale", numberScale)
        putBoolean("autoNumber", autoNumber)
        putInt("numberThreshold", numberThreshold)
        putBoolean("chargeColorAuto", chargeColorAuto)
        putInt("voocColor", voocColor)
        putInt("ufcsColor", ufcsColor)
        putInt("ppsColor", ppsColor)
        putInt("slowChargeColor", slowChargeColor)
        putInt("unknownColor", unknownColor)
    }

    companion object {
        const val PACKAGE = "io.github.threeinone"
        const val FILE = "indicator"
        const val REFRESH = "$PACKAGE.REFRESH"
        const val REFRESH_PERMISSION = "$PACKAGE.permission.REFRESH"
        const val PROBE_TOKEN = "probeToken"
        const val PROBE_STATUS = "probeStatus"
        const val PROBE_TIME = "probeTime"
        const val PROBE_REVISION = "probeRevision"

        /**
         * Rebuild from the cross-process bundle; falls back to defaults when unavailable.
         *
         * A bundle written before the palette was split into four protocol tones is
         * moved over to the new one, the same way the preferences are.
         */
        fun fromBundle(b: android.os.Bundle?): IndicatorConfig {
            val palette = paletteFromBundle(b)
            fun bool(key: String, def: Boolean) = b?.let { if (it.containsKey(key)) it.getBoolean(key) else def } ?: def
            fun int(key: String, def: Int) = b?.let { if (it.containsKey(key)) it.getInt(key) else def } ?: def
            return IndicatorConfig(
                enabled = bool("enabled", false),
                lockscreen = bool("lockscreen", true),
                size = int("size", 22).coerceIn(16, 44),
                wifiScale = int("wifiScale", 115).coerceIn(70, 150),
                textScale = int("textScale", 150).coerceIn(80, 250),
                stroke = int("stroke", 120).coerceIn(60, 160),
                dotScale = int("dotScale", 100).coerceIn(60, 140),
                dotSpacing = int("dotSpacing", 100).coerceIn(70, 125),
                horizontalPadding = int("horizontalPadding", 2).coerceIn(0, 8),
                verticalOffset = int("verticalOffset", 0).coerceIn(-4, 4),
                colors = bool("colors", true),
                chargingColor = int("chargingColor", 0xFF1CB753.toInt()),
                lowColor = int("lowColor", 0xFFE7433A.toInt()),
                criticalColor = int("criticalColor", 0xFFD50000.toInt()),
                saverColor = int("saverColor", 0xFFFFB300.toInt()),
                lowThreshold = int("lowThreshold", 20).coerceIn(5, 50),
                criticalThreshold = int("criticalThreshold", 10).coerceIn(1, 20),
                showVpn = bool("showVpn", true),
                swap = bool("swap", false),
                roamingColor = int("roamingColor", 0xFF2F80ED.toInt()),
                dualSim = bool("dualSim", true),
                showActivity = bool("showActivity", false),
                showNumber = bool("showNumber", false),
                numberScale = int("numberScale", 115).coerceIn(60, 140),
                autoNumber = bool("autoNumber", false),
                numberThreshold = int("numberThreshold", 20).coerceIn(1, 100),
                chargeColorAuto = bool("chargeColorAuto", true),
                voocColor = int("voocColor", 0xFF9C6BFF.toInt()),
                ufcsColor = palette.ufcsColor,
                ppsColor = palette.ppsColor,
                slowChargeColor = palette.slowChargeColor,
                unknownColor = palette.unknownColor
            )
        }

        fun normalizeNumberMode(p: SharedPreferences): Boolean {
            if (!p.getBoolean("showNumber", false) || !p.getBoolean("autoNumber", false)) return false
            p.edit().putBoolean("autoNumber", false).apply()
            return true
        }

        fun setNumberMode(p: SharedPreferences, key: String, enabled: Boolean) {
            require(key == "showNumber" || key == "autoNumber")
            val other = if (key == "showNumber") "autoNumber" else "showNumber"
            val editor = p.edit().putBoolean(key, enabled)
            if (enabled) editor.putBoolean(other, false)
            editor.apply()
        }
        /**
         * The colour of the plain charging slot before the palette was split into four
         * protocol tones. A setting that still holds it was never customised.
         */
        private const val LEGACY_PPS_COLOR = 0xFF2ECC71.toInt()

        /**
         * The colours the slow-charging slot held before the palette was split into four
         * protocol tones. A setting that still holds one of them was never customised.
         */
        private val LEGACY_SLOW_COLORS = setOf(0xFFFFB300.toInt(), 0xFF3B82F6.toInt())

        /**
         * Reads the settings and moves a palette that predates the four protocol tones
         * over to the new one.
         *
         * The tones changed meaning: the green that belonged to PPS is now the colour of
         * a third-party UFCS charger, and the blue that belonged to slow charging is now
         * the colour of a PPS charger. A value that still equals the old default was
         * never customised, so it can move along; a value the user picked stays where it
         * is.
         */
        private fun readPalette(p: SharedPreferences): Palette {
            // The new slots are absent from a palette that predates them.
            val migrated = !p.contains("ufcsColor") || !p.contains("unknownColor")
            val legacyPps = p.getInt("ppsColor", LEGACY_PPS_COLOR) or 0xFF000000.toInt()
            val legacySlow = p.getInt("slowChargeColor", 0xFF3B82F6.toInt()) or 0xFF000000.toInt()
            return Palette(
                // The green that belonged to PPS is now the tone of a third-party UFCS
                // charger, and the blue that belonged to slow charging the tone of PPS.
                ufcsColor = if (migrated) legacyPps else p.getInt("ufcsColor", LEGACY_PPS_COLOR) or 0xFF000000.toInt(),
                // PPS is the blue tone now; the old slow-charging colour moves into that
                // slot only when it was the blue default, otherwise the user picked it.
                ppsColor = if (migrated && legacySlow in LEGACY_SLOW_COLORS) {
                    0xFF3B82F6.toInt()
                } else {
                    p.getInt("ppsColor", 0xFF3B82F6.toInt()) or 0xFF000000.toInt()
                },
                // Slow charging gets a neutral grey of its own; a colour the user picked
                // for it stays.
                slowChargeColor = if (migrated && legacySlow in LEGACY_SLOW_COLORS) {
                    0xFF64748B.toInt()
                } else {
                    p.getInt("slowChargeColor", 0xFF64748B.toInt()) or 0xFF000000.toInt()
                },
                unknownColor = p.getInt("unknownColor", 0xFFF97316.toInt()) or 0xFF000000.toInt()
            )
        }

        /** The four charging tones, read from a cross-process bundle. */
        private fun paletteFromBundle(b: android.os.Bundle?): Palette {
            fun int(key: String, def: Int) = b?.let { if (it.containsKey(key)) it.getInt(key) else def } ?: def
            val migrated = b?.containsKey("ufcsColor") != true || b.containsKey("unknownColor") != true
            val legacyPps = int("ppsColor", LEGACY_PPS_COLOR) or 0xFF000000.toInt()
            val legacySlow = int("slowChargeColor", 0xFF3B82F6.toInt()) or 0xFF000000.toInt()
            return Palette(
                ufcsColor = if (migrated) legacyPps else int("ufcsColor", LEGACY_PPS_COLOR) or 0xFF000000.toInt(),
                // PPS is the blue tone now; the old slow-charging colour moves into that
                // slot only when it was the blue default, otherwise the user picked it.
                ppsColor = if (migrated && legacySlow in LEGACY_SLOW_COLORS) {
                    0xFF3B82F6.toInt()
                } else {
                    int("ppsColor", 0xFF3B82F6.toInt()) or 0xFF000000.toInt()
                },
                slowChargeColor = if (migrated && legacySlow in LEGACY_SLOW_COLORS) {
                    0xFF64748B.toInt()
                } else {
                    int("slowChargeColor", 0xFF64748B.toInt()) or 0xFF000000.toInt()
                },
                unknownColor = int("unknownColor", 0xFFF97316.toInt()) or 0xFF000000.toInt()
            )
        }

        /** The four charging tones, read from the settings. */
        private data class Palette(
            val ufcsColor: Int,
            val ppsColor: Int,
            val slowChargeColor: Int,
            val unknownColor: Int
        )

        fun read(p: SharedPreferences): IndicatorConfig {
            val palette = readPalette(p)
            return read(p, palette)
        }

        private fun read(p: SharedPreferences, palette: Palette) = IndicatorConfig(
            enabled = p.getBoolean("enabled", false), lockscreen = p.getBoolean("lockscreen", true),
            size = p.getInt("size", 22).coerceIn(16, 32),
            wifiScale = p.getInt("wifiScale", 115).coerceIn(70, 150),
            textScale = p.getInt("textScale", 150).coerceIn(80, 250),
            stroke = p.getInt("stroke", 120).coerceIn(60, 160),
            dotScale = p.getInt("dotScale", 100).coerceIn(60, 140),
            dotSpacing = p.getInt("dotSpacing", 100).coerceIn(70, 125),
            horizontalPadding = p.getInt("horizontalPadding", 2).coerceIn(0, 8),
            verticalOffset = p.getInt("verticalOffset", 0).coerceIn(-4, 4),
            colors = p.getBoolean("colors", true),
            chargingColor = p.getInt("chargingColor", 0xFF1CB753.toInt()) or 0xFF000000.toInt(),
            lowColor = p.getInt("lowColor", 0xFFE7433A.toInt()) or 0xFF000000.toInt(),
            criticalColor = p.getInt("criticalColor", 0xFFD50000.toInt()) or 0xFF000000.toInt(),
            saverColor = p.getInt("saverColor", 0xFFFFB300.toInt()) or 0xFF000000.toInt(),
            lowThreshold = p.getInt("lowThreshold", 20).coerceIn(5, 50),
            criticalThreshold = p.getInt("criticalThreshold", 10).coerceIn(1, 20),
            showVpn = p.getBoolean("showVpn", true),
            swap = p.getBoolean("swap", false),
            roamingColor = p.getInt("roamingColor", 0xFF2F80ED.toInt()) or 0xFF000000.toInt(),
            dualSim = p.getBoolean("dualSim", true),
            showActivity = p.getBoolean("showActivity", false),
            showNumber = p.getBoolean("showNumber", false),
            numberScale = p.getInt("numberScale", 115).coerceIn(60, 140),
            autoNumber = !p.getBoolean("showNumber", false) && p.getBoolean("autoNumber", false),
            numberThreshold = p.getInt("numberThreshold", 20).coerceIn(1, 100),
            chargeColorAuto = p.getBoolean("chargeColorAuto", true),
            voocColor = p.getInt("voocColor", 0xFF9C6BFF.toInt()) or 0xFF000000.toInt(),
            ufcsColor = palette.ufcsColor,
            ppsColor = palette.ppsColor,
            slowChargeColor = palette.slowChargeColor,
            unknownColor = palette.unknownColor
        )

        // WCAG-based visibility floor for custom status colours.
        // Mirrors status-trio's BatteryStatusColorResolver: a user colour whose worst-case
        // contrast against reference backgrounds falls below the floor is mixed toward
        // black/white just far enough to pass. The original selection is preserved in
        // SharedPreferences; only the drawn colour moves.
        fun ensureVisible(color: Int): Int {
            val floor = 1.2
            val base = toRgb(color)
            if (minContrast(base) >= floor) return color
            for (dest in listOf(Rgb(0.0, 0.0, 0.0), Rgb(1.0, 1.0, 1.0))) {
                for (step in 1..40) {
                    val candidate = mix(base, dest, step / 40.0)
                    if (minContrast(candidate) >= floor) return rgbToInt(candidate)
                }
            }
            return color
        }
    }
}

// ---- WCAG-based visibility floor for custom status colours ----
// Mirrors status-trio's BatteryStatusColorResolver: a user colour whose worst-case
// contrast against the reference menu backgrounds falls below the floor is mixed
// toward black/white just far enough to pass. The original selection is preserved
// in SharedPreferences; only the drawn colour moves.
private data class Rgb(val r: Double, val g: Double, val b: Double)
private fun toRgb(color: Int): Rgb = Rgb(
    (color shr 16 and 0xFF) / 255.0,
    (color shr 8 and 0xFF) / 255.0,
    (color and 0xFF) / 255.0
)
private fun rgbToInt(c: Rgb): Int {
    val r = (c.r * 255).toInt().coerceIn(0, 255)
    val g = (c.g * 255).toInt().coerceIn(0, 255)
    val b = (c.b * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
private fun linear(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
private fun relLum(c: Rgb) = 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b)
private fun contrast(a: Rgb, b: Rgb): Double {
    val l1 = relLum(a); val l2 = relLum(b)
    return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
}
private val VIS_REFS = listOf(Rgb(1.0, 1.0, 1.0), Rgb(0.0, 0.0, 0.0), Rgb(0.78, 0.78, 0.78), Rgb(0.22, 0.22, 0.22))
private fun minContrast(c: Rgb) = VIS_REFS.minOf { contrast(c, it) }
private fun mix(start: Rgb, end: Rgb, amount: Double) = Rgb(
    start.r + (end.r - start.r) * amount,
    start.g + (end.g - start.g) * amount,
    start.b + (end.b - start.b) * amount
)
