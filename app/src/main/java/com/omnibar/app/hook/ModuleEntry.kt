package com.omnibar.app.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed entry point (LSPosed 2.x, Xposed API 102).
 *
 * The entry class is registered in `META-INF/xposed/java_init.list`; the framework
 * instantiates it inside the SystemUI process and calls [onPackageLoaded] once the
 * package is loaded. The ColorOS contract is resolved lazily and defensively inside
 * [ColorOsAdapter], so a partially matching firmware degrades individual features
 * instead of disabling the module.
 */
class ModuleEntry : XposedModule() {
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != "com.android.systemui" || !param.isFirstPackage) return
        log(Log.INFO, "3IN1", "SystemUI entry")
        runCatching { ColorOsAdapter(param.defaultClassLoader, ::hook, ::log).install() }
            .onFailure { log(Log.ERROR, "3IN1", "adapter unavailable: $it") }
    }
}
