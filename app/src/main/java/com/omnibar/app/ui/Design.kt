package com.omnibar.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import kotlin.math.roundToInt

/**
 * The settings screen's design tokens.
 *
 * The app ships without a third-party UI dependency, so every surface is drawn from the
 * platform's own drawables. The palette follows the system's night mode.
 */
class Palette(context: Context) {
    private val dark = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Page background — FlClash-style light grey, slightly darker than the cards. */
    val background = if (dark) 0xFF17181A.toInt() else 0xFFF2F2F4.toInt()

    /** Card background — pure white on light, slightly raised grey on dark. */
    val surface = if (dark) 0xFF222326.toInt() else Color.WHITE

    /** A row nested inside a card. */
    val surfaceAlt = if (dark) 0xFF2C2E31.toInt() else 0xFFF0F0F2.toInt()

    /** Hairline between rows and around cards. */
    val stroke = if (dark) 0xFF333538.toInt() else 0xFFE7E7EA.toInt()

    val ink = if (dark) 0xFFF3F3F4.toInt() else 0xFF1A1B1D.toInt()
    val muted = if (dark) 0xFF9AA0A6.toInt() else 0xFF6B7075.toInt()

    /** FlClash brand blue, used as the single accent across both themes. */
    val accent = if (dark) 0xFF4C9AFF.toInt() else 0xFF2F6BFF.toInt()

    /** Tinted accent background, for chips and badges. */
    val accentSoft = if (dark) 0xFF1E2A3A.toInt() else 0xFFE9EFFD.toInt()

    /** Text and icon colour on top of [accent]. */
    val onAccent = if (dark) 0xFF0A1220.toInt() else Color.WHITE

    val danger = if (dark) 0xFFFF8A80.toInt() else 0xFFE7433A.toInt()

    /** Backdrop of the enlarged preview. */
    val previewBackdrop = if (dark) 0xFF101113.toInt() else 0xFFEAEAEE.toInt()
}

/** Converts dp to pixels. */
fun Context.dp(value: Int): Int = dp(value.toFloat())

/** Converts dp to pixels. */
fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

/** Converts dp to pixels. */
fun View.dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

/** A rounded rectangle in the given colour, optionally with a hairline border. */
fun Context.rounded(
    color: Int,
    radiusDp: Float,
    border: Int? = null,
    borderDp: Float = 1f
): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    if (border != null) setStroke(dp(borderDp).coerceAtLeast(1), border)
}

/** A fully rounded pill. */
fun Context.pill(color: Int, border: Int? = null): GradientDrawable =
    rounded(color, 999f, border)
