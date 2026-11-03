package io.github.threeinone.hook

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

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                current = BatterySnapshot.apply(current, intent)
                negotiatedCurrentMa = intent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, 0) / 1000
                negotiatedVoltageMv = intent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, 0) / 1000
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
    private val oplusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent == null) return
            oplusFastType = intent.readInt("fast_chg_type", oplusFastType)
            oplusPpsMode = intent.readInt("pps_chg_mode", oplusPpsMode)
            oplusChargeTechnology = intent.readInt("chargertechnology", oplusChargeTechnology)
            oplusChargePlugged = intent.readInt("chargeplugged", oplusChargePlugged)
            oplusChargerVoltageMv = intent.readInt("chargervoltage", oplusChargerVoltageMv)
            oplusWatts = intent.readInt("chargewattage", oplusWatts)
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

    private fun applyProtocol() {
        val protocol = detectProtocol()
        if (protocol != current.chargeProtocol || oplusWatts != current.chargeWatts) {
            XposedBridge.log(
                "3IN1: charge protocol=$protocol negotiated=${negotiatedVoltageMv}mV/${negotiatedCurrentMa}mA " +
                    "oplus=[fastType=$oplusFastType ppsMode=$oplusPpsMode tech=$oplusChargeTechnology " +
                    "plugged=$oplusChargePlugged fast=$oplusFastCharger v=${oplusChargerVoltageMv}mV w=${oplusWatts}]"
            )
        }
        current = current.copy(chargeProtocol = protocol, chargeWatts = oplusWatts)
    }

    /**
     * Protocol detection. The Oplus extras are authoritative when present:
     * `fast_chg_type` is non-zero while the proprietary VOOC rail is negotiated and
     * `pps_chg_mode` is non-zero while PPS is in use. Without them the negotiated
     * current/voltage still allow a rough fast-versus-slow guess.
     */
    private fun detectProtocol(): ChargeProtocol {
        if (oplusPpsMode > 0) return ChargeProtocol.PPS
        if (oplusFastType > 0) return ChargeProtocol.SUPER_VOOC
        if (oplusFastCharger || oplusChargeTechnology > 0) return ChargeProtocol.FAST
        // Plugged in, but the firmware reports no proprietary fast protocol: a plain
        // USB charger, i.e. the slow-charging case.
        if (oplusChargePlugged > 0) return ChargeProtocol.SLOW
        if (negotiatedVoltageMv > 0) {
            // 11 V is OnePlus/OPPO's proprietary SuperVOOC rail; PPS tops out around 9 V.
            if (negotiatedVoltageMv >= 10000) return ChargeProtocol.SUPER_VOOC
            if (negotiatedVoltageMv >= 9000 && negotiatedCurrentMa >= 4000) return ChargeProtocol.PPS
            return if (negotiatedVoltageMv >= 9000) ChargeProtocol.FAST else ChargeProtocol.SLOW
        }
        return ChargeProtocol.NONE
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
        override fun run() {
            if (!running) return
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
                XposedBridge.log("3IN1: sticky battery level=${current.battery} observed=${current.batteryObserved}")
            }
            ?: XposedBridge.log("3IN1: no sticky battery broadcast received")
        current = current.copy(powerSave = power.isPowerSaveMode, airplane = isAirplane())

        registerSignalListeners()
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
        val wifiConnected = wifiCaps != null
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

        /**
         * `BatteryManager.EXTRA_MAX_CHARGING_CURRENT` / `EXTRA_MAX_CHARGING_VOLTAGE` are
         * hidden in the public SDK, but the extra keys are stable AOSP contract and carry
         * the negotiated charger current (µA) and voltage (µV).
         */
        const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
        const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"
    }
}
