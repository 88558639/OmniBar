package com.omnibar.app.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes the app once after boot so the exported [ConfigProvider] is registered before
 * SystemUI asks for the settings.
 *
 * On a cold boot the app sits in the "stopped" state, so a cross-process provider call
 * fails with `Unknown authority` and the module falls back to a stale preferences cache.
 * Touching the preferences here publishes the provider to the system and clears that state.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        android.util.Log.i("3IN1", "boot receiver fired; publishing provider")
        runCatching {
            context.getSharedPreferences(IndicatorConfig.FILE, Context.MODE_PRIVATE).all
        }
    }
}
