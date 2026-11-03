package io.github.threeinone.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.threeinone.config.IndicatorConfig

/** Re-launch the overlay after boot when the user previously enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences(IndicatorConfig.FILE, Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (enabled && OverlayService.canDrawOverlays(context)) {
            OverlayService.start(context)
        }
    }
}
