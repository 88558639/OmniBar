package io.github.threeinone.ui

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

    /** Page background — slightly off-white so the cards read as raised. */
    val background = if (dark) 0xFF0E1011.toInt() else 0xFFF4F7F8.toInt()

    /** Card background. */
    val surface = if (dark) 0xFF191C1E.toInt() else Color.WHITE

    /** A row nested inside a card. */
    val surfaceAlt = if (dark) 0xFF23282B.toInt() else 0xFFF1F4F5.toInt()

    /** Hairline between rows and around cards. */
    val stroke = if (dark) 0xFF2D3336.toInt() else 0xFFE4E9EB.toInt()

    val ink = if (dark) 0xFFF1F4F5.toInt() else 0xFF191F21.toInt()
    val muted = if (dark) 0xFF98A8AE.toInt() else 0xFF5E6D73.toInt()

    /** The brand teal, already the app's accent colour. */
    val accent = if (dark) 0xFF63D7C4.toInt() else 0xFF007F70.toInt()

    /** Tinted accent background, for chips and badges. */
    val accentSoft = if (dark) 0xFF11302C.toInt() else 0xFFE1F2EF.toInt()

    /** Text and icon colour on top of [accent]. */
    val onAccent = if (dark) 0xFF05201C.toInt() else Color.WHITE

    val danger = if (dark) 0xFFFF8A80.toInt() else 0xFFE7433A.toInt()

    /** Backdrop of the enlarged preview. */
    val previewBackdrop = if (dark) 0xFF101314.toInt() else 0xFFEDF1F2.toInt()
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
