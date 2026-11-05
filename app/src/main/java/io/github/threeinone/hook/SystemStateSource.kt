package io.github.threeinone.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import de.robv.android.xposed.XposedBridge
import io.github.threeinone.model.BatteryPhase
import io.github.threeinone.model.BatterySnapshot
import io.github.threeinone.model.ChargeProtocol
import io.github.threeinone.model.DataActivity
import io.github.threeinone.model.IndicatorSim
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.model.SimPhase
import io.github.threeinone.model.WifiStatus
import java.util.concurrent.Executors

/**
 * Collects the indicator state from public Android APIs only — no reflection into SystemUI
 * internals. This keeps the state layer independent of the OEM's private classes, while the
 * view layer ([ColorOsAdapter]) follows SuperIcon's ColorOS contract.
 *
 * Everything degrades gracefully: without READ_PHONE_STATE the cellular fields stay UNKNOWN
 * and the battery / Wi-Fi parts still render.
 */
class SystemStateSource(
    private val context: Context,
    private val changed: (IndicatorState) -> Unit,
    private val failed: (Throwable) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val subs = context.getSystemService(SubscriptionManager::class.java)
    private val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

    private var current = IndicatorState()
    private var running = false

    private var wifiCaps: NetworkCapabilities? = null
    private val simStates = LinkedHashMap<Int, IndicatorSim>()
    private val simServices = HashMap<Int, ServiceState>()
    private val simCallbacks = mutableMapOf<Int, Pair<TelephonyManager, TelephonyCallback>>()

    // Charging protocol telemetry: the standard negotiated values plus Oplus' own extras.
    private var negotiatedCurrentMa = 0
    private var negotiatedVoltageMv = 0
    private var oplusFastType = 0
    private var oplusPpsMode = 0
    private var oplusChargeTechnology = 0
    private var oplusChargePlugged = 0
    private var oplusFastCharger = false
    private var oplusChargerVoltageMv = 0
    private var oplusWatts = 0

    /**
     * The charge type the charger IC negotiated, read from the battery service.
     *
     * Oplus' `fast_chg_type` broadcast extra stays 0 on UFCS chargers — the real value
     * only reaches the system through the charger service.
     */
    private var batteryFastChgType = 0

    /** The charger technology level the charging IC reports. */
    private var chargerTechnologyLevel = 0

    /** Whether the charger is running the PPS protocol, as the charging IC reports it. */
    private var ppsChgIng = 0

    /**
     * The highest power this charger session negotiated.
     *
     * A full battery draws no power at all, so the live reading drops to zero once
     * the phone is full even though the original charger is still plugged in. Keeping
     * the peak keeps the colour stable over the whole session; the value is dropped
     * when the charger is unplugged.
     */
    private var peakWatts = 0

    /** Last serialized SIM snapshot, so we only log when the rendering input actually changes. */
    private var lastSimsSig: String? = null

    /** Last sysfs-derived protocol, so the diagnostic line does not repeat every tick. */
    private var lastSysfsProtocol: ChargeProtocol? = null

    /**
     * Oplus sysfs nodes that carry the negotiated charging protocol. These are the
     * exact sources the 电池检测 app (`com.shellterm.batterycheck`) and the `*#899#`
     * engineering-mode "快充模式" field read, so the indicator's colour now matches
     * what the user sees there.
     *
     * The app resolves the displayed protocol in this priority (its `fastChargeText`):
     *   1. `voocchg_ing` == 1            → original OnePlus/OPPO SuperVOOC rail (violet)
     *   2. `ppschg_ing`   == 4            → UFCS third-party charger (green)
     *   3. `ppschg_ing`   == 2            → PPS charger (blue)
     *   4. `charge_type` integer          → 14=SVOOC, 16=PD, 8=VOOC, 2=normal
     *   5. `charge_technology` str/int    → VOOC/SUPERVOOC/UFCS/PPS/PD (name or code)
     *
     * On this PLK110 firmware `charge_technology` reports an integer (e.g. `14`)
     * rather than the protocol-name string other Oplus builds expose, so both forms
     * are handled below.
     */
    private val VOOC_ING_PATH = "/sys/class/oplus_chg/battery/voocchg_ing"
    private val PPS_ING_PATH = "/sys/class/oplus_chg/battery/ppschg_ing"
    private val CHARGE_TECHNOLOGY_PATHS = listOf(
        "/sys/class/oplus_chg/battery/charge_technology"
    )

    /**
     * Oplus sysfs node that carries the charge type as an integer (0 none, 2 normal,
     * 8 VOOC-basic, 14 SuperVOOC, 16 PD …). The app's primary protocol source when the
     * `voocchg_ing` / `ppschg_ing` flags above are unset.
     */
    private val CHARGE_TYPE_PATHS = listOf(
        "/sys/class/power_supply/battery/charge_type",
        "/sys/class/power_supply/bms/charge_type"
    )

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                current = BatterySnapshot.apply(current, intent)
                negotiatedCurrentMa = intent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, 0) / 1000
                negotiatedVoltageMv = intent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, 0) / 1000
                // Unplugged: the next charger starts its own session.
                if (current.batteryPhase == BatteryPhase.NORMAL) peakWatts = 0
                refreshChargerTelemetry()
                applyProtocol()
                emit()
            }
        }
    }

    /**
     * Oplus broadcasts the negotiated protocol on its own actions; the extras are the ones
     * declared by `android.os.OplusBatteryManager` (`fast_chg_type`, `pps_chg_mode`,
     * `chargefastcharger`, `chargervoltage`, …).
     */
    /** Fires whenever a SIM is added, removed, enabled or disabled (SuperIcon's approach). */
    private val subListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            handler.post {
                refreshSubscriptions()
                schedule()
            }
        }
    }

    private val oplusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent == null) return
            oplusFastType = intent.readInt("fast_chg_type", oplusFastType)
            oplusPpsMode = intent.readInt("pps_chg_mode", oplusPpsMode)
            oplusChargeTechnology = intent.readInt("chargertechnology", oplusChargeTechnology)
            oplusChargePlugged = intent.readInt("chargeplugged", oplusChargePlugged)
            // Only the full battery broadcast carries the voltage; the short one omits it
            // and would otherwise keep a stale reading.
            if (intent.extras?.containsKey("chargervoltage") == true) {
                oplusChargerVoltageMv = intent.readInt("chargervoltage", 0)
            }
            // `chargewattage` is deliberately not read here: the broadcast extra lags
            // behind when the charger is unplugged, while the charger service reports
            // the current negotiation.
            oplusFastCharger = intent.readBoolean("chargefastcharger", oplusFastCharger)
            applyProtocol()
            emit()
        }
    }

    private fun Intent.readInt(key: String, fallback: Int): Int = when (val v = getExtras()?.get(key)) {
        is Int -> v
        is Long -> v.toInt()
        is String -> v.toIntOrNull() ?: fallback
        else -> fallback
    }

    private fun Intent.readBoolean(key: String, fallback: Boolean): Boolean = when (val v = getExtras()?.get(key)) {
        is Boolean -> v
        is Int -> v != 0
        is String -> v.toIntOrNull()?.let { it != 0 } ?: fallback
        else -> fallback
    }

    /**
     * Reads the first line of a sysfs node, tolerating nodes that are missing or not
     * readable on this device (non-Oplus firmware, SELinux denials). A null result
     * lets the caller fall back to the broadcast / voltage heuristic.
     */
    private fun readSysfsLine(path: String): String? = runCatching {
        val f = java.io.File(path)
        if (!f.canRead()) return@runCatching null
        f.bufferedReader().use { it.readLine()?.trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Reads an integer sysfs node, returning null when it is missing / unreadable / not numeric. */
    private fun readSysfsInt(path: String): Int? = readSysfsLine(path)?.toIntOrNull()

    /**
     * Reads the negotiated charge protocol straight from the Oplus charging driver's
     * sysfs nodes — the same data the `*#899#` engineering mode "快充模式" field and the
     * 电池检测 app display, resolved in the exact priority that app uses
     * (`fastChargeText`):
     *
     *  1. `voocchg_ing` == 1 → original OnePlus/OPPO SuperVOOC rail (violet)
     *  2. `ppschg_ing`   == 4 → third-party UFCS charger (green)
     *  3. `ppschg_ing`   == 2 → PPS charger (blue)
     *  4. `charge_technology` / `charge_type` integer or name → mapped below
     *
     * `charge_technology` is a protocol-name string on most Oplus firmwares but an
     * integer code (e.g. `14`) on this PLK110 build, so both forms are handled. The
     * caller only invokes this while charging, which keeps the stale value the node
     * can hold while idle from leaking through.
     */
    private fun protocolFromSysfs(): ChargeProtocol? {
        // 1) Original OnePlus/OPPO SuperVOOC rail — only the proprietary charger sets this.
        if (readSysfsInt(VOOC_ING_PATH) == 1) return ChargeProtocol.SUPER_VOOC
        // 2) Public fast-charge protocols, split by what the charger IC reports.
        when (readSysfsInt(PPS_ING_PATH)) {
            4 -> return ChargeProtocol.UFCS // third-party UFCS charger
            2 -> return ChargeProtocol.PPS  // PPS charger
        }
        // 3) `charge_technology`: a protocol-name string on most Oplus builds, but an
        //    integer code on this PLK110 firmware, so handle both.
        CHARGE_TECHNOLOGY_PATHS.firstNotNullOfOrNull { readSysfsLine(it) }?.let { tech ->
            val up = tech.uppercase()
            if (up.contains("SUPERVOOC") || up.contains("VOOC")) return ChargeProtocol.SUPER_VOOC
            if (up.contains("UFCS")) return ChargeProtocol.UFCS
            if (up.contains("PPS")) return ChargeProtocol.PPS
            if (up.contains("PD")) return ChargeProtocol.PPS // PD/PPS family → blue
            tech.toIntOrNull()?.let { return mapChargeTechInt(it) }
        }
        // 4) `charge_type` integer — the app's primary protocol source when the flags
        //    above are unset.
        CHARGE_TYPE_PATHS.firstNotNullOfOrNull { readSysfsInt(it) }?.let { type ->
            if (type > 0) return mapChargeTechInt(type)
        }
        return null
    }

    /**
     * Maps an Oplus `charge_type` / `charge_technology` integer code onto a protocol,
     * matching the 电池检测 app's `protocolName` table
     * (0=none, 2=normal, 8=VOOC-basic, 14=SVOOC, 16=PD) plus the user's colour scheme.
     */
    private fun mapChargeTechInt(code: Int): ChargeProtocol = when (code) {
        14 -> ChargeProtocol.SUPER_VOOC // SVOOC — original OnePlus/OPPO rail
        8 -> ChargeProtocol.SUPER_VOOC  // VOOC-basic — original OnePlus/OPPO rail
        16 -> ChargeProtocol.PPS        // PD / PPS family — USB-C fast charge
        2 -> ChargeProtocol.SLOW        // normal 5V/9V — plain charger (orange)
        else -> ChargeProtocol.FAST     // any other fast code
    }

    private fun applyProtocol() {
        val charging = current.batteryPhase in setOf(BatteryPhase.CHARGING, BatteryPhase.FULL)
        // The Oplus driver's sysfs node is the authoritative protocol; only fall back
        // to the broadcast / voltage heuristic when it is unavailable (non-Oplus
        // firmware) or the charger is idle (the node can hold a stale value).
        val sysfs = if (charging) protocolFromSysfs() else null
        val protocol = sysfs ?: detectProtocol()
        if (protocol != current.chargeProtocol || oplusWatts != current.chargeWatts ||
            sysfs != lastSysfsProtocol
        ) {
            val tech = CHARGE_TECHNOLOGY_PATHS.firstNotNullOfOrNull { readSysfsLine(it) } ?: "n/a"
            val type = CHARGE_TYPE_PATHS.firstNotNullOfOrNull { readSysfsLine(it) } ?: "n/a"
            val voocIng = readSysfsLine(VOOC_ING_PATH) ?: "n/a"
            val ppsIng = readSysfsLine(PPS_ING_PATH) ?: "n/a"
            XposedBridge.log(
                "3IN1: charge protocol=$protocol (sysfs=$sysfs node=[tech=$tech type=$type voocIng=$voocIng ppsIng=$ppsIng]) " +
                    "negotiated=${negotiatedVoltageMv}mV/${negotiatedCurrentMa}mA " +
                    "oplus=[fastType=$oplusFastType ppsMode=$oplusPpsMode tech=$oplusChargeTechnology " +
                    "plugged=$oplusChargePlugged fast=$oplusFastCharger v=${oplusChargerVoltageMv}mV w=${oplusWatts}] " +
                    "battery=[fastChgType=$batteryFastChgType chgTech=$chargerTechnologyLevel " +
                    "chgV=${oplusChargerVoltageMv}mV ppsIng=$ppsChgIng maxV=${negotiatedVoltageMv}mV]"
            )
            lastSysfsProtocol = sysfs
        }
        current = current.copy(chargeProtocol = protocol, chargeWatts = oplusWatts)
    }

    /** Detects the charging protocol from the collected telemetry. */
    private fun detectProtocol(): ChargeProtocol = detectProtocol(
        chargeTech = batteryFastChgType,
        fastType = oplusFastType,
        ppsMode = oplusPpsMode,
        chargeTechnology = maxOf(chargerTechnologyLevel, oplusChargeTechnology),
        plugged = oplusChargePlugged,
        fastCharger = oplusFastCharger,
        voltageMv = negotiatedVoltageMv,
        currentMa = negotiatedCurrentMa,
        wattage = peakWatts,
        chargerVoltageMv = oplusChargerVoltageMv,
        charging = current.batteryPhase in setOf(BatteryPhase.CHARGING, BatteryPhase.FULL)
    )

    /**
     * Reads the charge technology the charger IC negotiated.
     *
     * The Oplus battery service keeps the real value in its `usb_fast_chg_type`
     * property, but the broadcast extra `fast_chg_type` stays 0 on UFCS chargers. The
     * value is therefore read from the `guardelfthermalcontrol` service, whose
     * `getPsyChargeTech()` returns exactly what the charger HAL negotiated.
     *
     * A failed read keeps the last known value, so a transient service hiccup does not
     * flip the colour back and forth.
     */
    private fun refreshChargerTelemetry() {
        runCatching { queryGuardElf(TRANSACTION_GET_PSY_CHARGE_TECH) }.getOrNull()
            ?.let { if (it >= 0) batteryFastChgType = it }
        // The negotiated power is what tells an original OnePlus charger from a
        // third-party one that happens to speak UFCS as well.
        runCatching { queryGuardElf(TRANSACTION_GET_CHARGER_TECHNOLOGY) }.getOrNull()
            ?.let { if (it >= 0) chargerTechnologyLevel = it }
        runCatching { queryGuardElf(TRANSACTION_GET_BATT_PPS_CHG_ING) }.getOrNull()
            ?.let { if (it >= 0) ppsChgIng = it }
        runCatching { queryGuardElf(TRANSACTION_GET_BATT_PPS_CHG_POWER) }.getOrNull()
            ?.let {
                if (it >= 0) {
                    oplusWatts = it
                    if (it > peakWatts) peakWatts = it
                }
            }
    }

    /**
     * Fetches a system service binder.
     *
     * `ServiceManager.getService` is a hidden API, so it is called reflectively.
     */
    private fun serviceBinder(name: String): android.os.IBinder? = runCatching {
        val clazz = Class.forName("android.os.ServiceManager")
        val method = clazz.getMethod("getService", String::class.java)
        method.invoke(null, name) as? android.os.IBinder
    }.getOrNull()

    /**
     * Calls a no-argument `int` getter on the `guardelfthermalcontrol` service.
     *
     * The interface is a hidden API, so it is reached over Binder directly: the
     * service binder is fetched from ServiceManager and the method is invoked with the
     * transaction code its stub uses.
     */
    private fun queryGuardElf(transaction: Int): Int? {
        val binder = serviceBinder(SERVICE_GUARD_ELF_THERMAL) ?: return null
        val parcel = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        return try {
            parcel.writeInterfaceToken(INTERFACE_GUARD_ELF_THERMAL)
            parcel.writeInt(0)
            if (!binder.transact(transaction, parcel, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } finally {
            parcel.recycle()
            reply.recycle()
        }
    }

    private val miscReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    current = current.copy(powerSave = power.isPowerSaveMode)
                    emit()
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    current = current.copy(airplane = isAirplane())
                    schedule()
                }
                // The user moved the data card between slots.
                ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED -> schedule()
                // A card was enabled, disabled or removed.
                ACTION_SIM_STATE_CHANGED,
                ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED -> schedule()
            }
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifiCaps = caps
            schedule()
        }

        override fun onLost(network: Network) {
            val active = connectivity.activeNetwork
            wifiCaps = active?.let { connectivity.getNetworkCapabilities(it) }
                ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            schedule()
        }
    }

    private val activityPoll = object : Runnable {
        private var lastRx = -1L
        private var lastTx = -1L
        private var tick = 0
            override fun run() {
            if (!running) return
            // While charging, re-read the Oplus driver node so the colour follows the
            // negotiated protocol even if no battery broadcast fires mid-negotiation.
            if (current.batteryPhase in setOf(BatteryPhase.CHARGING, BatteryPhase.FULL)) {
                refreshChargerTelemetry()
                applyProtocol()
            }
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val traffic = if (lastRx >= 0 && lastTx >= 0) {
                val din = rx > lastRx
                val dout = tx > lastTx
                when {
                    din && dout -> DataActivity.BOTH
                    din -> DataActivity.IN
                    dout -> DataActivity.OUT
                    else -> DataActivity.NONE
                }
            } else DataActivity.NONE
            lastRx = rx
            lastTx = tx
            // Re-read the subscriptions now and then so a card that was switched off
            // disappears from the indicator even if no telephony callback arrives.
            if (tick++ % 4 == 0) buildCurrent()
            current = current.copy(
                wifiActivity = if (current.wifiConnected) traffic else DataActivity.NONE,
                mobileActivity = maxOf(current.mobileActivity, traffic)
            )
            emit()
            handler.postDelayed(this, 1500)
        }
    }

    fun start() {
        if (running) return
        running = true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
        context.registerReceiver(miscReceiver, IntentFilter().apply {
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
            addAction(ACTION_SIM_STATE_CHANGED)
            addAction(ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        // Oplus' own battery broadcast carries the negotiated charging protocol.
        runCatching {
            context.registerReceiver(oplusReceiver, IntentFilter().apply {
                addAction(OPLUS_BATTERY_CHANGED)
                addAction(OPLUS_UI_BATTERY_CHANGED)
            }, Context.RECEIVER_EXPORTED)
        }.onFailure { XposedBridge.log("3IN1: oplus charge broadcast unavailable: $it") }

        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            netCallback, handler
        )

        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
            ?.let {
                current = BatterySnapshot.apply(current, it)
                refreshChargerTelemetry()
                XposedBridge.log("3IN1: sticky battery level=${current.battery} observed=${current.batteryObserved}")
            }
            ?: XposedBridge.log("3IN1: no sticky battery broadcast received")
        current = current.copy(powerSave = power.isPowerSaveMode, airplane = isAirplane())

        registerSignalListeners()
        runCatching { subs.addOnSubscriptionsChangedListener(executor, subListener) }
            .onFailure { XposedBridge.log("3IN1: subscription listener unavailable: $it") }
        schedule()
        handler.postDelayed(activityPoll, 1500)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(activityPoll)
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(miscReceiver) }
        runCatching { context.unregisterReceiver(oplusReceiver) }
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
        simCallbacks.values.forEach { (tm, cb) -> runCatching { tm.unregisterTelephonyCallback(cb) } }
        simCallbacks.clear()
        runCatching { subs.removeOnSubscriptionsChangedListener(subListener) }
        simStates.clear()
        simServices.clear()
    }

    /** Exact Wi-Fi traffic direction, fed by the ColorOS wifi-activity hook. */
    fun setWifiActivity(direction: Int) {
        val act = when (direction) {
            1 -> DataActivity.IN
            2 -> DataActivity.OUT
            3 -> DataActivity.BOTH
            else -> DataActivity.NONE
        }
        current = current.copy(wifiActivity = act)
        emit()
    }

    private fun canReadPhone() = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED

    private fun registerSignalListeners() {
        if (!canReadPhone()) {
            schedule()
            return
        }
        refreshSubscriptions()
        schedule()
    }

    /**
     * Pick up SIM cards that appear after we started — on boot the telephony stack
     * registers the subscriptions a few seconds late, so a single read at start-up
     * would leave the indicator without any cellular signal.
     */
    @SuppressLint("MissingPermission")
    private fun refreshSubscriptions() {
        if (!canReadPhone()) return
        val active = runCatching { subs.activeSubscriptionInfoList }.getOrNull().orEmpty()
        val present = active.map { it.subscriptionId }.toSet()
        (simStates.keys - present).forEach { id ->
            simStates.remove(id)
            simCallbacks.remove(id)?.let { (tm, cb) ->
                runCatching { tm.unregisterTelephonyCallback(cb) }
            }
        }
        active.filter { it.subscriptionId !in simStates }.forEach { info ->
            registerSim(info.subscriptionId, info.simSlotIndex)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSim(id: Int, slot: Int) {
        val tm = if (SubscriptionManager.isValidSubscriptionId(id)) {
            runCatching { telephony.createForSubscriptionId(id) }.getOrDefault(telephony)
        } else telephony
        simStates[id] = IndicatorSim(
            slot = slot,
            phase = SimPhase.UNKNOWN,
            dataEnabled = runCatching { tm.isDataEnabled }.getOrDefault(false)
        )
        val callback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.DataActivityListener,
            TelephonyCallback.ServiceStateListener {
            override fun onSignalStrengthsChanged(s: SignalStrength) {
                val level = s.level.coerceIn(0, 4)
                updateSim(id) { current ->
                    // A non-zero level means the radio has a live signal. Some firmwares
                    // report a stale/out-of-service ServiceState while the bearer is up,
                    // so the strength is the more reliable indicator here.
                    val phase = if (level > 0 && current.phase != SimPhase.ABSENT &&
                        current.phase != SimPhase.LOCKED
                    ) SimPhase.READY else current.phase
                    current.copy(level = level, phase = phase)
                }
                schedule()
            }

            override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                val label = IndicatorState.networkLabel(
                    displayInfo.networkType, displayInfo.overrideNetworkType
                )
                updateSim(id) { it.copy(networkType = label) }
                schedule()
            }

            override fun onDataActivity(direction: Int) {
                val act = when (direction) {
                    TelephonyManager.DATA_ACTIVITY_IN -> DataActivity.IN
                    TelephonyManager.DATA_ACTIVITY_OUT -> DataActivity.OUT
                    TelephonyManager.DATA_ACTIVITY_INOUT -> DataActivity.BOTH
                    else -> DataActivity.NONE
                }
                updateSim(id) { it.copy(activity = act) }
                schedule()
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                simServices[id] = serviceState
                updateSim(id) { current ->
                    val phase = when {
                        serviceState.state == ServiceState.STATE_IN_SERVICE -> SimPhase.READY
                        // Keep READY while the radio still reports signal strength.
                        current.level > 0 -> SimPhase.READY
                        else -> SimPhase.NO_SIGNAL
                    }
                    current.copy(phase = phase, roaming = serviceState.roaming)
                }
                schedule()
            }
        }
        simCallbacks[id] = tm to callback
        runCatching { tm.registerTelephonyCallback(executor, callback) }
            .onFailure { XposedBridge.log("3IN1: signal listener for sub $id unavailable: $it") }
    }

    private fun updateSim(id: Int, transform: (IndicatorSim) -> IndicatorSim) {
        val prev = simStates[id] ?: return
        simStates[id] = transform(prev)
    }

    private fun isAirplane() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    private val snapshot = Runnable {
        if (running) {
            buildCurrent()
            emit()
        }
    }

    private fun schedule() {
        handler.removeCallbacks(snapshot)
        handler.post(snapshot)
    }

    private fun buildCurrent() {
        refreshSubscriptions()
        val wifiEnabled = wifi?.isWifiEnabled == true
        // Derive the Wi-Fi connection from the active network rather than the sticky
        // TRANSPORT_WIFI callback value. The callback value can lag when the device
        // switches to mobile data and leave `wifiCaps` set, which made the indicator
        // paint a grey Wi-Fi fan instead of the 5G/4G label.
        val activeCaps = runCatching {
            connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull()
        val wifiActive = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val wifiConnected = wifiActive
        val wifiCaps = if (wifiActive) activeCaps else null
        val wifiPhase = WifiStatus.phase(wifiEnabled, false, wifiConnected, wifiCaps)
        val rssi = wifi?.connectionInfo?.rssi ?: -200
        val wifiLevel = if (wifiConnected) {
            WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
        } else 0

        val defaultSub = SubscriptionManager.getDefaultDataSubscriptionId()
        // The user can move the data card between slots; follow it live so the left half
        // always shows the main card and the right half the secondary one.
        simStates.keys.toList().forEach { id ->
            simStates[id]?.let { if (it.dataDefault != (id == defaultSub)) {
                simStates[id] = it.copy(dataDefault = id == defaultSub)
            } }
        }
        val sims = simStates.values.sortedBy { it.slot }
        val primary = simStates[defaultSub] ?: sims.firstOrNull()
        val simPhase = when {
            primary != null -> primary.phase
            telephony.simState == TelephonyManager.SIM_STATE_ABSENT -> SimPhase.ABSENT
            else -> SimPhase.UNKNOWN
        }
        val vpn = runCatching {
            connectivity.allNetworks.any { n ->
                connectivity.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)

        current = current.copy(
            wifi = wifiPhase,
            wifiLevel = wifiLevel,
            sim = simPhase,
            mobileLevel = primary?.level ?: 0,
            networkType = primary?.networkType ?: "",
            dataEnabled = primary?.dataEnabled ?: false,
            roaming = primary?.roaming ?: false,
            vpn = vpn,
            sims = sims,
            networkReady = simPhase != SimPhase.UNKNOWN || current.airplane || wifiConnected
        )
        val sig = sims.joinToString(prefix = "[", postfix = "]") { "${it.slot}:${it.level}:${it.phase}" }
        if (sig != lastSimsSig) {
            lastSimsSig = sig
            XposedBridge.log("3IN1: sims=$sig defaultSub=$defaultSub")
        }
    }

    private fun emit() {
        changed(current)
    }

    companion object {
        /** `android.os.OplusBatteryManager.ACTION_ADDITIONAL_BATTERY_CHANGED`. */
        const val OPLUS_BATTERY_CHANGED = "android.intent.action.ADDITIONAL_BATTERY_CHANGED"

        /** `android.os.OplusBatteryManager.ACTION_UI_BATTERY_CHANGED`. */
        const val OPLUS_UI_BATTERY_CHANGED = "android.intent.action.UI_BATTERY_CHANGED"

        /** Broadcast sent when the user moves the data card between slots. */
        const val ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED =
            "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED"

        /** Broadcast sent when a SIM card is enabled, disabled or removed. */
        const val ACTION_SIM_STATE_CHANGED = "android.telephony.action.SIM_STATE_CHANGED"
        const val ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED =
            "android.telephony.action.SUBSCRIPTION_CARRIER_IDENTITY_CHANGED"

        /**
         * `BatteryManager.EXTRA_MAX_CHARGING_CURRENT` / `EXTRA_MAX_CHARGING_VOLTAGE` are
         * hidden in the public SDK, but the extra keys are stable AOSP contract and carry
         * the negotiated charger current (µA) and voltage (µV).
         */
        /**
         * The charge technology the charger IC negotiated, as reported by
         * `getPsyChargeTech()`.
         *
         * These are the `usb_fast_chg_type` codes used by the Oplus charger HAL:
         * 3 = SuperVOOC, 4 = PPS, 6 = UFCS. The battery service also reports a
         * coarser `usb_charge_technology` code (14 on UFCS) through the same call.
         */
        const val FAST_CHG_TYPE_VOOC = 3
        const val FAST_CHG_TYPE_PPS = 4
        const val FAST_CHG_TYPE_UFCS = 6

        /** The coarser charge technology code the battery service reports for UFCS. */
        const val CHARGE_TECH_UFCS = 14

        /**
         * The charger technology level from which on the charger is the proprietary
         * OnePlus/OPPO one.
         *
         * The charging IC reports the capabilities of whatever is plugged in: the
         * original charger reaches the VOOC level, a plain USB charger stays at 0.
         */
        const val CHARGER_TECHNOLOGY_VOOC = 3

        /**
         * The negotiated voltage from which on the charger counts as a fast charger.
         *
         * The USB baseline is 5 V; UFCS and PPS push the rail up to 9 V and beyond.
         * A plain USB port — even one in a power strip — negotiates 5 V only, even
         * though the firmware reports the UFCS charge type for it.
         */
        const val FAST_CHARGE_VOLTAGE_MV = 9000

        /**
         * The voltage from which on the charger is treated as speaking UFCS rather
         * than PPS.
         *
         * PPS negotiates in 9 V steps and stays at or below 9 V in practice, while
         * UFCS goes up to 10 V and beyond. The firmware reports the same charge type
         * for both, so the negotiated voltage is the only thing that separates them.
         */
        const val UFCS_HIGH_VOLTAGE_MV = 10000

        /** The `guardelfthermalcontrol` service that exposes the charger state. */
        const val SERVICE_GUARD_ELF_THERMAL = "guardelfthermalcontrol"

        /** Its Binder interface. */
        const val INTERFACE_GUARD_ELF_THERMAL = "android.os.IGuardElfThermalControl"

        /** Transaction code of `getPsyChargeTech()` in that interface. */
        const val TRANSACTION_GET_PSY_CHARGE_TECH = 25

        /** Transaction code of `getBattPPSChgPower()`, the negotiated power in watts. */
        const val TRANSACTION_GET_BATT_PPS_CHG_POWER = 22

        /** Transaction code of `getBattPPSChgIng()`, the PPS protocol flag. */
        const val TRANSACTION_GET_BATT_PPS_CHG_ING = 21

        /**
         * Transaction code of `getChargerTechnology()`, the charger capability level.
         *
         * The charging IC reports what the plugged-in charger can do: the original
         * OnePlus charger reaches the VOOC level, a plain USB charger stays at 0.
         */
        const val TRANSACTION_GET_CHARGER_TECHNOLOGY = 7

        /**
         * Maps the charging telemetry onto a protocol.
         *
         * The violet tone belongs to OnePlus' own SuperVOOC rail, which only the
         * original charger negotiates — and on this firmware the original charger
         * reports UFCS, not SuperVOOC, because UFCS is the public USB fast-charge
         * standard the charger also speaks. The protocol alone therefore cannot tell
         * an original charger from a third-party one that happens to support UFCS.
         *
         * The negotiated power does: only the original charger reaches the 100 W and
         * above range, so a high wattage is the marker for the proprietary rail.
         * Everything else falls back to the protocol the firmware reports.
         */
        fun detectProtocol(
            chargeTech: Int,
            fastType: Int,
            ppsMode: Int,
            chargeTechnology: Int,
            plugged: Int,
            fastCharger: Boolean,
            voltageMv: Int,
            currentMa: Int,
            wattage: Int,
            chargerVoltageMv: Int,
            charging: Boolean
        ): ChargeProtocol {
            // A charger that is not charging negotiates nothing, so the colour stays
            // neutral — the driver node can hold a stale value while idle. The power
            // alone cannot decide either: a full battery draws none, even on the
            // original charger.
            if (!charging) return ChargeProtocol.NONE
            // The original OnePlus charger: the charging IC reports the proprietary
            // VOOC capability. This is what tells it apart from a third-party charger
            // that speaks the public UFCS standard — the original charger speaks both,
            // and UFCS alone therefore says nothing about who plugged it in.
            if (chargeTechnology >= CHARGER_TECHNOLOGY_VOOC) return ChargeProtocol.SUPER_VOOC
            // The charge type alone does not identify a fast charger: the firmware
            // reports UFCS for a plain USB port as well — a power strip's port for
            // instance. What separates them is the voltage the charger actually
            // delivers: a fast charger pushes the rail to 9 V and beyond, a plain USB
            // port stays at 5 V.
            if (chargerVoltageMv >= FAST_CHARGE_VOLTAGE_MV) {
                // PPS negotiates in 9 V steps and tops out well below the proprietary
                // rail, while UFCS reaches the higher voltages. The firmware reports
                // the same charge type for both, so the voltage is what tells them
                // apart.
                if (chargeTech == FAST_CHG_TYPE_PPS) return ChargeProtocol.PPS
                if (chargeTech == CHARGE_TECH_UFCS) {
                    return if (chargerVoltageMv < UFCS_HIGH_VOLTAGE_MV) {
                        ChargeProtocol.PPS
                    } else {
                        ChargeProtocol.UFCS
                    }
                }
                if (chargeTech == FAST_CHG_TYPE_VOOC) return ChargeProtocol.SUPER_VOOC
            }
            if (fastType > 0) return ChargeProtocol.SUPER_VOOC
            // The charge technology is unknown but the firmware reports a proprietary
            // fast charger: fast, but not one of the named rails.
            if (fastCharger || chargeTechnology > 0) return ChargeProtocol.FAST
            // Plugged in, but the firmware reports no proprietary fast protocol: a plain
            // USB charger, i.e. the slow-charging case.
            if (plugged > 0) return ChargeProtocol.SLOW
            if (voltageMv > 0) {
                // 11 V is OnePlus/OPPO's proprietary SuperVOOC rail; PPS tops out around 9 V.
                if (voltageMv >= 10000) return ChargeProtocol.SUPER_VOOC
                if (voltageMv >= 9000 && currentMa >= 4000) return ChargeProtocol.PPS
                return if (voltageMv >= 9000) ChargeProtocol.FAST else ChargeProtocol.SLOW
            }
            return ChargeProtocol.NONE
        }

        const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
        const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"
    }
}
