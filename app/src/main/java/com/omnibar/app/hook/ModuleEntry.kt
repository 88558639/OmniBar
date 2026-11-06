package com.omnibar.app.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Legacy Xposed entry point (LSPosed supports legacy modules natively).
 *
 * The ColorOS contract is resolved lazily and defensively inside [ColorOsAdapter], so a
 * partially matching firmware degrades individual features instead of disabling the module.
 */
class ModuleEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.android.systemui" || param.processName != "com.android.systemui") return
        XposedBridge.log("3IN1: SystemUI entry")
        runCatching { ColorOsAdapter(param.classLoader).install() }
            .onFailure { XposedBridge.log("3IN1: adapter unavailable: $it") }
    }
}
