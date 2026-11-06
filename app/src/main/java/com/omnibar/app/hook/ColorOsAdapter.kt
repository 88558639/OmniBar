package com.omnibar.app.hook

import android.animation.LayoutTransition
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedInterface
import com.omnibar.app.config.IndicatorConfig
import com.omnibar.app.model.BatteryPhase
import com.omnibar.app.model.IndicatorState
import com.omnibar.app.render.IndicatorView
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * ColorOS 17 status-bar adapter.
 *
 * The mechanics mirror SuperIcon 1.7.23, which is verified working on this firmware:
 *  - the battery view `StatBatteryMeterView` is the mount point; the indicator is added as a
 *    **child view** of it (rather than painting into its canvas),
 *  - the native battery artwork is hidden by forcing every sibling to GONE and keeping it
 *    GONE through a `View.setVisibility` hook,
 *  - the native Wi-Fi / mobile icons are suppressed through
 *    `StatusIconContainer.addIgnoredSlot(...)` instead of poking `mIgnoredSlots`,
 *  - the on-screen tint is taken from the native pipeline
 *    (`binding` -> `$viewModel` -> `LocationBasedBatteryViewModel.getBatteryColor()` ->
 *    `StateFlow.getValue()` -> `BatteryIconColor.getSingleToneColor()`),
 *  - the charging phase comes from `ChargeStateModel` (including SuperVOOC / wireless VOOC).
 *
 * Every reflective member is resolved independently: a missing member degrades that single
 * feature instead of aborting the whole module (the previous implementation validated the
 * whole contract up-front and silently gave up, which is why nothing appeared).
 */
class ColorOsAdapter(
    private val loader: ClassLoader,
    private val hook: (Executable) -> XposedInterface.HookBuilder,
    private val log: (Int, String, String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    /** Background worker for the cross-process settings bridge. */
    private val io = Executors.newSingleThreadExecutor()
    private var context: Context? = null
    private var config = IndicatorConfig()
    private var state = IndicatorState()
    private var revision = 0L
    private var fault: String? = null
    private var failed = false

    private var batteryBinding: Field? = null
    private var ignoredSlots: Field? = null
    private var addIgnoredSlot: Method? = null
    private var removeIgnoredSlot: Method? = null
    private var batteryColors: Method? = null
    private var chargeStateField: Field? = null
    private var nativeCharging: Method? = null
    private var nativeFastCharging: Method? = null
    private var nativeWirelessCharging: Method? = null
    private var flowValue: Method? = null
    private var singleTone: Method? = null

    private val sessions = IdentityHashMap<ViewGroup, Session>()
    private var changingVisibility = false

    private var source: SystemStateSource? = null
    private var pendingProbeToken: String? = null
    private var lastFingerprint: List<Any> = emptyList()
    private var lastConfigSource = "none"
    private var providerRetries = 0
    private var mirrorFile: java.io.File? = null
    private var mirrorRevision = 0L

    /** One mounted indicator, bound to one battery view. */
    private inner class Session(val host: ViewGroup, val root: ViewGroup, val lockscreen: Boolean) {
        val indicator = IndicatorView(host.context)
        var active = false
        var savedWidth: Int? = null
        var transitionOwned = false
        var savedTransition: LayoutTransition? = null
        val unclipped = mutableListOf<ViewGroup>()
        val addedSlots = IdentityHashMap<View, MutableList<String>>()
        val hidden = IdentityHashMap<View, Int>()
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            main.removeCallbacks(update); main.post(update)
        }
        val beforeDraw = ViewTreeObserver.OnPreDrawListener {
            if (!failed && indicator.visibility == View.VISIBLE) {
                runCatching { hideNativeBattery(this) }
            }
            true
        }
    }

    private val update = Runnable { guarded { refresh() } }

    // ------------------------------------------------------------------ install

    fun install() {
        val battery = loader.loadClass(BATTERY)
        val icons = loader.loadClass(ICONS)

        batteryBinding = opt { battery.getDeclaredField("binding").apply { isAccessible = true } }
        ignoredSlots = opt { icons.getField("mIgnoredSlots") }
        addIgnoredSlot = opt { icons.getMethod("addIgnoredSlot", String::class.java) }
        removeIgnoredSlot = opt { icons.getMethod("removeIgnoredSlot", String::class.java) }

        val vm = opt { loader.loadClass(BATTERY_VIEW_MODEL) }
        batteryColors = vm?.let { opt { it.getMethod("getBatteryColor") } }
        chargeStateField = vm?.let { opt { it.getDeclaredField("chargeState").apply { isAccessible = true } } }
        val csm = opt { loader.loadClass(CHARGE_STATE_MODEL) }
        nativeCharging = csm?.let { opt { it.getMethod("getCharging") } }
        nativeFastCharging = csm?.let { opt { it.getMethod("getSuperVoocCharging") } }
        nativeWirelessCharging = csm?.let { opt { it.getMethod("getWirelessVoocCharging") } }
        flowValue = opt { loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue") }
        singleTone = opt { loader.loadClass(BATTERY_ICON_COLOR).getMethod("getSingleToneColor") }

        log(Log.INFO, "3IN1",
" contract resolved " +
                "(binding=${batteryBinding != null} slots=${addIgnoredSlot != null} " +
                "color=${batteryColors != null} tone=${singleTone != null} charge=${nativeCharging != null})"
        )

        // Mount point: the battery view itself.
        hookAllAfter(battery, "onAttachedToWindow") { c ->
            val host = c.getThisObject() as? ViewGroup ?: return@hookAllAfter
            host.post { guarded { if (host.isAttachedToWindow) attach(host, lockscreen = false) } }
        }
        hookAllAfter(battery, "onDetachedFromWindow") { c ->
            guarded { detach(c.getThisObject() as? ViewGroup ?: return@guarded) }
        }

        // Keep suppressed icons hidden even when SystemUI re-shows them.
        hookBefore(View::class.java, "setVisibility", Int::class.javaPrimitiveType!!) { c ->
            if (changingVisibility) return@hookBefore
            val view = c.getThisObject() as View
            for (session in sessions.values) {
                if (session.hidden.containsKey(view)) {
                    c.getArgs()[0] = View.GONE
                    return@hookBefore
                }
            }
        }

        // Suppress layout animations while the indicator is mounted.
        hookBefore(ViewGroup::class.java, "setLayoutTransition", LayoutTransition::class.java) { c ->
            if (Looper.myLooper() != Looper.getMainLooper() || changingVisibility) return@hookBefore
            for (session in sessions.values) {
                if (session.host === c.getThisObject() && session.transitionOwned) {
                    session.savedTransition = c.getArgs()[0] as LayoutTransition?
                    c.getArgs()[0] = null
                    return@hookBefore
                }
            }
        }

        // Lockscreen battery view (the main bar is covered by onAttachedToWindow above).
        runCatching {
            hookAllAfter(loader.loadClass(KEYGUARD_STATUS_BAR), "onFinishInflate") { c ->
                val root = c.getThisObject() as? ViewGroup ?: return@hookAllAfter
                root.post { guarded { scanForBattery(root, lockscreen = true) } }
            }
        }.onFailure { log(Log.INFO, "3IN1", "lockscreen fallback unavailable: $it") }

        // Exact Wi-Fi traffic direction (SuperIcon's hook); optional.
        runCatching {
            val callbackClass = loader.loadClass(WIFI_ACTIVITY_CALLBACK)
            hookAfter(callbackClass, "onStateChanged", Int::class.javaPrimitiveType!!) { c ->
                val direction = c.getArgs().getOrNull(0) as? Int ?: return@hookAfter
                main.post { source?.setWifiActivity(direction) }
            }
        }.onFailure { log(Log.INFO, "3IN1", "wifi-activity hook unavailable: $it") }

        log(Log.INFO, "3IN1", "hooks installed")
    }

    /**
     * Hooks every method of [clazz] matching [name] and runs [block] after the original
     * method returns, preserving its result. Failure inside [block] is routed to [fail].
     */
    private fun hookAllAfter(clazz: Class<*>, name: String, block: (XposedInterface.Chain) -> Unit) {
        for (m in clazz.declaredMethods) {
            if (m.name != name) continue
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try { block(chain) } catch (error: Throwable) { fail(error) }
                    result
                }
        }
    }

    /**
     * Hooks a single method by exact signature. [block] runs before `proceed()`, so it can
     * rewrite arguments (`chain.getArgs()` is a mutable list) or skip the original call.
     */
    private fun hookBefore(
        clazz: Class<*>,
        name: String,
        vararg params: Class<*>,
        block: (XposedInterface.Chain) -> Unit
    ) {
        val m = clazz.getDeclaredMethod(name, *params)
        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try { block(chain) } catch (error: Throwable) { fail(error) }
                chain.proceed()
            }
    }

    /**
     * Hooks a single method by exact signature and runs [block] after it returns.
     */
    private fun hookAfter(
        clazz: Class<*>,
        name: String,
        vararg params: Class<*>,
        block: (XposedInterface.Chain) -> Unit
    ) {
        val m = clazz.getDeclaredMethod(name, *params)
        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try { block(chain) } catch (error: Throwable) { fail(error) }
                result
            }
    }

    private inline fun <T> opt(block: () -> T): T? = try {
        block()
    } catch (error: Throwable) {
        log(Log.INFO, "3IN1", "optional member unavailable: ${error.javaClass.simpleName}: ${error.message}")
        null
    }

    // ------------------------------------------------------------------ lifecycle

    private fun scanForBattery(root: ViewGroup, lockscreen: Boolean) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.javaClass.name == BATTERY) {
                    attach(child as ViewGroup, lockscreen)
                    return
                }
            }
            for (i in 0 until root.childCount) {
                (root.getChildAt(i) as? ViewGroup)?.let { scanForBattery(it, lockscreen) }
            }
        }
    }

    private fun attach(host: ViewGroup, lockscreen: Boolean) {
        if (failed || sessions.containsKey(host)) return
        val root = findIconRow(host) ?: run {
            log(Log.INFO, "3IN1", "no icon row next to the battery view; keeping native icons")
            return
        }
        initialize(host.context)
        val session = Session(host, root, lockscreen)
        sessions[host] = session
        // The indicator is lifted out of the battery view, which is parked at the bottom of
        // the status bar. Nothing on the way up may clip it.
        var clipper: ViewGroup? = host
        while (clipper != null) {
            if (clipper.clipChildren) {
                session.unclipped.add(clipper)
                clipper.clipChildren = false
            }
            clipper = clipper.parent as? ViewGroup
        }
        // Alignment reference: the native icon (bluetooth / alarm) the indicator has to
        // share a line with. Without one the indicator keeps its original place — that is
        // where it already lined up with the other icons.
        session.indicator.alignmentAnchor = findNativeIcon(root)
        logHierarchy(session)
        host.addView(session.indicator, ViewGroup.LayoutParams(
            (config.size * density(root)).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        root.viewTreeObserver.addOnGlobalLayoutListener(session.layoutListener)
        root.viewTreeObserver.addOnPreDrawListener(session.beforeDraw)
        manageSource()
        refresh(layout = true)
        report()
    }

    private fun detach(host: ViewGroup) {
        val session = sessions.remove(host) ?: return
        if (session.root.viewTreeObserver.isAlive) {
            session.root.viewTreeObserver.removeOnGlobalLayoutListener(session.layoutListener)
            session.root.viewTreeObserver.removeOnPreDrawListener(session.beforeDraw)
        }
        restore(session)
        host.removeView(session.indicator)
        manageSource()
    }

    /** SuperIcon's "adjacent icon row" lookup: walk up until a StatusIconContainer is found. */
    private fun findIconRow(view: ViewGroup): ViewGroup? {
        var parent = view.parent
        var depth = 0
        while (parent is ViewGroup && depth < 6) {
            if (parent.javaClass.name == ICONS) return parent
            val nested = findNestedIconContainer(parent)
            if (nested != null) return nested
            parent = parent.parent
            depth++
    }
        return null
    }

    /** The StatusIconContainer inside a wrapper, if there is one. */
    private fun findNestedIconContainer(view: ViewGroup): ViewGroup? {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.javaClass.name == ICONS) return child as ViewGroup
            if (child is ViewGroup) {
                val deep = findNestedIconContainer(child)
                if (deep != null) return deep
            }
        }
        return null
    }

    /**
     * The native icon the indicator has to share a horizontal line with.
     *
     * On this firmware the battery sits on the **left** of the status bar, next to the
     * clock, the alarm and the bluetooth icon. Those icons are the reference: the
     * indicator is centred on one of them, which is a lot more precise than the icon
     * row container (that one also spans the whole bar).
     */
    private fun findNativeIcon(root: ViewGroup): View? {
        var best: View? = null
        collectNativeIcons(root) { view ->
            if (view.visibility != View.VISIBLE || view.height <= 0) return@collectNativeIcons
            if (best == null) best = view
        }
        return best
    }

    /** Walks the icon row and reports every bluetooth / alarm icon it contains. */
    private fun collectNativeIcons(view: View, onFound: (View) -> Unit) {
        val simple = view.javaClass.simpleName
        val entry = runCatching {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else ""
        }.getOrDefault("")
        val name = "$simple/$entry".lowercase()
        if (name.contains("bluetooth") || name.contains("alarm")) onFound(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectNativeIcons(view.getChildAt(i), onFound)
        }
    }

    /**
     * Dumps the placement of the indicator and of the native icons.
     *
     * The vertical alignment cannot be checked from a screenshot alone (the status bar is
     * only a few pixels tall), so the numbers are written to the module log.
     */
    private fun logHierarchy(session: Session) {
        val anchor = session.indicator.alignmentAnchor
        val rect = Rect()
        anchor?.getGlobalVisibleRect(rect)
        val hostRect = Rect()
        session.host.getGlobalVisibleRect(hostRect)
        val rowRect = Rect()
        session.root.getGlobalVisibleRect(rowRect)
        log(Log.INFO, "3IN1",
" anchor=${anchor?.javaClass?.simpleName} anchorRect=${rect.top}..${rect.bottom} " +
                "host=${session.host.javaClass.simpleName} hostRect=${hostRect.top}..${hostRect.bottom} " +
                "row=${session.root.javaClass.simpleName} rowRect=${rowRect.top}..${rowRect.bottom}"
        )
        val names = mutableListOf<String>()
        collectNativeIcons(session.root) { view ->
            val r = Rect()
            view.getGlobalVisibleRect(r)
            names.add("${view.javaClass.simpleName}@${r.top}..${r.bottom}")
        }
        if (names.isNotEmpty()) log(Log.INFO, "3IN1", "native icons ${names.joinToString(", ")}")
    }

    private fun initialize(c: Context) {
        if (context != null) return
        context = c
        // Mirror of the app settings; lives in the SystemUI process so the module keeps
        // working while the app process is stopped (fresh install, boot, app killed).
        mirrorFile = runCatching {
            val dir = runCatching { c.filesDir }.getOrNull()
                ?: java.io.File(c.applicationInfo.dataDir, "files")
            java.io.File(dir, "3in1-mirror.conf")
        }.getOrNull()
        log(Log.INFO, "3IN1", "mirror file=${mirrorFile?.absolutePath}")
        c.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == IndicatorConfig.REFRESH) {
                    log(Log.INFO, "3IN1", "refresh broadcast received, reloading settings")
                    intent.getStringExtra(IndicatorConfig.PROBE_TOKEN)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { pendingProbeToken = it }
                    reload()
                }
            }
        }, IntentFilter(IndicatorConfig.REFRESH), IndicatorConfig.REFRESH_PERMISSION, main,
            Context.RECEIVER_EXPORTED)
        reload()
    }

    private fun reload() {
        main.post {
            loadConfig()
            val token = pendingProbeToken.also { pendingProbeToken = null }
            report(token)
        }
    }

    /**
     * Settings cross the process boundary through [com.omnibar.app.config.ConfigProvider];
     * the legacy shared-preferences file is only a fallback.
     *
     * Both live channels require the app process to be running. On Android 15 a freshly
     * installed app sits in the "stopped" state, so neither the provider nor the
     * shared-preferences bridge answers and the module would fall back to an all-default
     * configuration (indicator switched off). A mirror of the last known settings is kept
     * inside the SystemUI process for exactly that case.
     *
     * The provider is queried off the main thread: it is a cross-process call and the app
     * process may be frozen, which would otherwise stall the whole status bar.
     */
    private fun loadConfig(): IndicatorConfig {
        val local = readLocalConfig()
        if (local != null) {
            applyConfig(local.first, local.second)
            fetchProviderConfig()
            return local.first
        }
        fetchProviderConfig()
        return IndicatorConfig()
    }

    /** Locally available settings: the in-process mirror file as the only local source. */
    private fun readLocalConfig(): Pair<IndicatorConfig, String>? {
        val mirror = readMirror()
        return if (mirror != null) mirror to "mirror" else null
    }

    /** Queries the app for the live settings in the background and applies them. */
    private fun fetchProviderConfig() {
        val ctx = context ?: return
        io.execute {
            val bundle = runCatching {
                ctx.contentResolver.call(
                    Uri.parse("content://${IndicatorConfig.PACKAGE}.config"), "config", null, null)
            }.onFailure { log(Log.INFO, "3IN1", "provider call failed: ${it.message}") }.getOrNull()
            val rev = runCatching {
                ctx.contentResolver.call(
                    Uri.parse("content://${IndicatorConfig.PACKAGE}.config"), "revision", null, null)
                    ?.getLong("revision") ?: 0L
            }.getOrDefault(0L)
            main.post {
                if (bundle != null) {
                    providerRetries = 0
                    applyConfig(IndicatorConfig.fromBundle(bundle), "provider", rev)
                } else {
                    scheduleProviderRetry()
                }
            }
        }
    }

    /** Stores the settings and refreshes the mounted indicators. */
    private fun applyConfig(cfg: IndicatorConfig, source: String, rev: Long = revision) {
        config = cfg
        revision = if (source == "mirror") mirrorRevision else rev
        if (source == "provider" || source == "xsharedprefs") writeMirror(cfg, rev)
        lastConfigSource = source
        log(Log.INFO, "3IN1",
" config[$source] enabled=${cfg.enabled} dualSim=${cfg.dualSim} " +
                "size=${cfg.size} dotScale=${cfg.dotScale} swap=${cfg.swap} " +
                "showActivity=${cfg.showActivity} showVpn=${cfg.showVpn}"
        )
        android.util.Log.i("3IN1", "config[$source] enabled=${cfg.enabled} dualSim=${cfg.dualSim}")
        guarded {
            manageSource()
            refresh(layout = true)
        }
    }

    /**
     * Mirror of the app settings, kept inside the SystemUI process.
     *
     * It is a plain file instead of a shared preference: the status-bar process cannot
     * rely on the preference services here (Android 15 redirects them into the APEX area
     * and the SystemUI package has no preference directory at all), while its own files
     * directory is always writable.
     */
    private fun writeMirror(cfg: IndicatorConfig, rev: Long) {
        runCatching {
            val file = mirrorFile ?: return
            val bundle = cfg.toBundle()
            val text = buildString {
                for (key in bundle.keySet()) {
                    @Suppress("DEPRECATION")
                    val value = when (val v = bundle.get(key)) {
                        is Boolean -> "b:${if (v) 1 else 0}"
                        is Int -> "i:$v"
                        is Long -> "l:$v"
                        is String -> "s:$v"
                        else -> continue
                    }
                    append(key).append('=').append(value).append('\n')
                }
                append("revision=l:").append(rev).append('\n')
            }
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { log(Log.INFO, "3IN1", "mirror write failed: ${it.message}") }
    }

    private fun readMirror(): IndicatorConfig? = runCatching {
        val file = mirrorFile ?: return null
        if (!file.isFile) return null
        val values = file.readLines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
        if (values.isEmpty()) return null
        val bundle = android.os.Bundle()
        for ((key, raw) in values) {
            when {
                raw.startsWith("b:") -> bundle.putBoolean(key, raw.substring(2) == "1")
                raw.startsWith("i:") -> raw.substring(2).toIntOrNull()?.let { bundle.putInt(key, it) }
                raw.startsWith("l:") -> raw.substring(2).toLongOrNull()?.let { bundle.putLong(key, it) }
                raw.startsWith("s:") -> bundle.putString(key, raw.substring(2))
            }
        }
        mirrorRevision = values["revision"]?.removePrefix("l:")?.toLongOrNull() ?: 0L
        IndicatorConfig.fromBundle(bundle)
    }.getOrNull()

    /**
     * Re-tries the provider bridge while it is still unreachable (typically right after a
     * reboot, when the app has not been started yet) and applies the settings as soon as the
     * live values arrive.
     */
    private fun scheduleProviderRetry() {
        if (providerRetries >= 4) return
        val delay = when (providerRetries) {
            0 -> 5_000L
            1 -> 15_000L
            2 -> 45_000L
            else -> 120_000L
        }
        providerRetries++
        main.postDelayed({ fetchProviderConfig() }, delay)
    }

    // ------------------------------------------------------------------ state

    private fun manageSource() {
        val shouldRun = !failed && config.enabled && sessions.isNotEmpty()
        if (!shouldRun) {
            source?.stop(); source = null
            state = IndicatorState()
        } else if (source == null && context != null) {
            source = SystemStateSource(context!!, { next ->
                val wasReady = state.ready
                val fingerprint = listOf(next.battery, next.batteryObserved, next.networkReady,
                    next.sim, next.wifi, next.airplane) +
                    next.sims.map { "${it.slot}:${it.level}:${it.dataDefault}" }
                val changed = fingerprint != lastFingerprint
                state = next
                if (changed) {
                    lastFingerprint = fingerprint
                    log(Log.INFO, "3IN1",
                        "state battery=${next.battery} observed=${next.batteryObserved} " +
                            "netReady=${next.networkReady} sim=${next.sim} wifi=${next.wifi} " +
                            "airplane=${next.airplane} level=${next.mobileLevel} " +
                            "dots=${next.dots} sims=${next.sims.size} " +
                            next.sims.joinToString("") {
                                "[slot${it.slot} lvl=${it.level} default=${it.dataDefault}]"
                            }
                    )
                }
                guarded { refresh(); if (wasReady != state.ready) report() }
            }, { fail(it) }, log).also { it.start() }
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun refresh(layout: Boolean = false) {
        for (session in sessions.values) {
            val active = !failed && config.enabled && state.ready &&
                (!session.lockscreen || config.lockscreen)
            if (active) {
                session.indicator.tint = resolveTint(session)
                session.indicator.state = refineCharging()
                session.indicator.config = config
                val width = (config.size * density(session.root)).toInt()
                if (session.savedWidth == null) session.savedWidth = session.host.layoutParams?.width ?: -1
                if (!session.transitionOwned) {
                    session.savedTransition = session.host.layoutTransition
                    session.transitionOwned = true
                    changingVisibility = true
                    try {
                        session.host.layoutTransition = null
                    } finally {
                        changingVisibility = false
                    }
                }
                setWidth(session.host, width)
                session.indicator.layoutParams = (session.indicator.layoutParams
                    ?: ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)).apply {
                    this.width = width
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                suppress(session.root, session)
                hideNativeBattery(session)
                session.indicator.visibility = View.VISIBLE
                session.active = true
            } else {
                restore(session)
                session.active = false
            }
            if (layout) session.host.requestLayout()
            session.host.invalidate()
        }
    }

    /** Native tint straight from the ColorOS battery pipeline (SuperIcon's chain). */
    private fun resolveTint(session: Session): Int {
        val fallback = Color.WHITE
        return try {
            val binding = batteryBinding?.get(session.host) ?: return fallback
            val model = binding.javaClass.getField("\$viewModel").get(binding)
            val flow = batteryColors?.invoke(model) ?: return fallback
            val value = flowValue?.invoke(flow) ?: return fallback
            (singleTone?.invoke(value) as? Int) ?: fallback
        } catch (error: Throwable) {
            fallback
        }
    }

    /** Refine the charging phase with the native ChargeStateModel (SuperVOOC aware). */
    private fun refineCharging(): IndicatorState {
        val charging = nativeCharging ?: return state
        val fast = nativeFastCharging
        val wireless = nativeWirelessCharging
        return try {
            val binding = batteryBinding?.get(sessions.values.firstOrNull()?.host) ?: return state
            val model = binding.javaClass.getField("\$viewModel").get(binding)
            val chargeState = chargeStateField?.get(model) ?: return state
            val isCharging = charging.invoke(chargeState) as? Boolean ?: false
            val isFast = (fast?.invoke(chargeState) as? Boolean ?: false) ||
                (wireless?.invoke(chargeState) as? Boolean ?: false)
            when {
                isCharging && state.battery >= 100 -> state.copy(batteryPhase = BatteryPhase.FULL)
                isCharging || isFast -> state.copy(batteryPhase = BatteryPhase.CHARGING)
                else -> state
            }
        } catch (error: Throwable) {
            state
        }
    }

    private fun setWidth(view: View, width: Int) {
        val params = view.layoutParams ?: return
        if (params.width != width) {
            params.width = width
            view.layoutParams = params
        }
    }

    /**
     * Hide the native Wi-Fi / mobile / airplane icons and blank their slots.
     *
     * Two mechanisms, because the icons live in different places on this firmware:
     *  - `addIgnoredSlot` on the `StatusIconContainer` drops the network icons from the
     *    measurement pass, so they lose their space as well as their artwork;
     *  - the airplane icon is a `StatIconView` in a **sibling** container, so it is not
     *    reached by the slot list at all. It is matched by its slot name and hidden
     *    directly — otherwise the status bar shows the native plane next to the drawn one.
     */
    private fun suppress(view: View, session: Session) {
        if (view === session.host || view === session.indicator) return
        if (view.javaClass.name == ICONS) {
            val current = ignoredSlots?.get(view) as? Collection<*> ?: emptyList<Any>()
            val added = session.addedSlots.getOrPut(view) { mutableListOf() }
            for (slot in NETWORK_SLOTS) {
                if (!current.contains(slot)) {
                    runCatching { addIgnoredSlot?.invoke(view, slot) }
                    if (!added.contains(slot)) added.add(slot)
                }
            }
        }
        if (slotOf(view) in TAKEOVER_SLOTS) {
            hide(view, session)
            return
        }
        val simpleName = view.javaClass.simpleName
        if (simpleName.contains("StatusBarMobileView") || simpleName.contains("StatusBarWifiView")) {
            hide(view, session)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) suppress(view.getChildAt(i), session)
        }
    }

    /** The slot name of an icon view, or an empty string when it does not have one. */
    private fun slotOf(view: View): String = slotNameOf(view)

    private fun hideNativeBattery(session: Session) {
        for (i in 0 until session.host.childCount) {
            val child = session.host.getChildAt(i)
            if (child !== session.indicator) hide(child, session)
        }
    }

    private fun hide(view: View, session: Session) {
        if (!session.hidden.containsKey(view)) session.hidden[view] = view.visibility
        changingVisibility = true
        try {
            if (view.visibility != View.GONE) view.visibility = View.GONE
        } finally {
            changingVisibility = false
        }
    }

    private fun restore(session: Session) {
        session.indicator.visibility = View.GONE
        for (group in session.unclipped) {
            runCatching {
                group.clipChildren = true
                group.clipToPadding = true
            }
        }
        session.unclipped.clear()
        for ((view, visibility) in session.hidden.toMap()) {
            session.hidden.remove(view)
            runCatching { view.visibility = visibility }
        }
        for ((view, slots) in session.addedSlots) {
            for (slot in slots) runCatching { removeIgnoredSlot?.invoke(view, slot) }
        }
        session.addedSlots.clear()
        session.savedWidth?.let { setWidth(session.host, it) }
        session.savedWidth = null
        if (session.transitionOwned) {
            session.transitionOwned = false
            runCatching { session.host.layoutTransition = session.savedTransition }
            session.savedTransition = null
        }
    }

    private fun density(view: View) = view.resources.displayMetrics.density

    // ------------------------------------------------------------------ reporting

    private fun report(probeToken: String? = null) {
        val ctx = context ?: return
        val text = when {
            fault != null -> "已回退原图标：$fault"
            !config.enabled -> "模块已加载；替换开关关闭"
            sessions.isEmpty() -> "模块已加载；等待状态栏电池视图"
            !state.ready -> "模块已加载；等待完整系统状态"
            sessions.values.any { it.active } -> "模块已运行"
            else -> "模块已加载；当前显示位置未启用"
        }
        log(Log.INFO, "3IN1", "$text; revision=$revision; hosts=${sessions.size}")
        main.post {
            runCatching {
                ctx.contentResolver.call(
                    Uri.parse("content://${IndicatorConfig.PACKAGE}.diagnostics"), "report", null,
                    android.os.Bundle().apply {
                        putString("status", text)
                        putLong("revision", revision)
                        if (!probeToken.isNullOrBlank()) putString(IndicatorConfig.PROBE_TOKEN, probeToken)
                    })
            }.onFailure {
                // A swallowed failure here leaves the settings screen showing a stale
                // receipt, so the reason has to reach the log.
                log(Log.INFO, "3IN1", "report failed: $it")
            }
        }
    }

    private fun fail(error: Throwable) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { fail(error) }
            return
        }
        if (failed) return
        failed = true
        fault = "${error.javaClass.simpleName}: ${error.message?.take(160)}"
        runCatching { source?.stop() }
        source = null
        sessions.values.forEach { session ->
            runCatching { restore(session) }
            runCatching { session.host.removeView(session.indicator) }
        }
        sessions.clear()
        changingVisibility = true
        try {
            sessions.values.forEach { session ->
                session.hidden.toMap().forEach { (view, visibility) ->
                    runCatching { view.visibility = visibility }
                }
                session.hidden.clear()
            }
        } finally {
            changingVisibility = false
        }
        log(Log.INFO, "3IN1", "restored stock icons: $error")
        report()
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            fail(error)
        }
    }

    companion object {
        const val BATTERY = "com.oplus.systemui.statusbar.pipeline.battery.ui.view.StatBatteryMeterView"
        const val ICONS = "com.android.systemui.statusbar.phone.StatusIconContainer"
        const val KEYGUARD_STATUS_BAR = "com.android.systemui.statusbar.phone.KeyguardStatusBarView"
        const val BATTERY_VIEW_MODEL =
            "com.oplus.systemui.statusbar.pipeline.battery.ui.viewmodel.LocationBasedBatteryViewModel"
        const val CHARGE_STATE_MODEL =
            "com.oplus.systemui.statusbar.pipeline.battery.data.model.ChargeStateModel"
        const val BATTERY_ICON_COLOR =
            "com.oplus.systemui.statusbar.pipeline.battery.ui.model.BatteryIconColor"
        const val WIFI_ACTIVITY_CALLBACK =
            "com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl\$wifiActivity\$1\$callback\$1"
        /**
         * The native status bar slots the indicator takes over.
         *
         * `StatusIconContainer` keeps a set of ignored slots: a child whose `getSlot()`
         * matches one of them is left out of the measurement pass, so the icon disappears
         * and its space is given to the next one. The names come from the framework
         * strings `status_bar_wifi` / `status_bar_mobile` / `status_bar_airplane_slot`.
         *
         * "airplane" matters because the indicator draws the flight-mode plane itself
         * (it replaces the Wi-Fi fan in the middle band). Without ignoring that slot the
         * status bar shows the native plane *and* the drawn one — two planes side by side.
         */
        val NETWORK_SLOTS = listOf("wifi", "mobile", "stacked_mobile", "airplane")

        /**
         * The slots whose native icon view is hidden outright.
         *
         * The airplane icon is a `StatIconView` that lives outside the `StatusIconContainer`
         * the slot list applies to, so it has to be hidden by view. Its slot name comes
         * from the framework string `status_bar_airplane_slot` ("airplane"), the same
         * naming the other system status icons use.
         */
        val TAKEOVER_SLOTS = setOf("airplane")

        /**
         * Reads the slot name an icon view reports.
         *
         * Every icon view implements `StatusIconDisplayable.getSlot()`; the name comes
         * from the framework strings (`status_bar_alarm`, `status_bar_airplane_slot`, …).
         * A view without that method — the indicator itself, plain containers — has no
         * slot and is never taken over.
         */
        internal fun slotNameOf(view: View): String = runCatching {
            view.javaClass.getMethod("getSlot").invoke(view) as? String
        }.getOrNull().orEmpty()
    }
}
