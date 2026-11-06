package com.omnibar.app.hook

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
import android.util.Log
import com.omnibar.app.model.BatteryPhase
import com.omnibar.app.model.BatterySnapshot
import com.omnibar.app.model.ChargeProtocol
import com.omnibar.app.model.DataActivity
import com.omnibar.app.model.IndicatorSim
import com.omnibar.app.model.IndicatorState
import com.omnibar.app.model.SimPhase
import com.omnibar.app.model.WifiStatus
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
    private val failed: (Throwable) -> Unit,
    private val log: (Int, String, String) -> Unit
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
     * Oplus sysfs nodes that carry the "快充模式" protocol name. These are the exact
     * sources the 电池检测 app (`com.shellterm.batterycheck`) reads, so the indicator
     * colour matches what the user sees there.
     *
     * On most Oplus builds `charge_technology` carries the protocol name as a string
     * (VOOC / SUPERVOOC / UFCS / PPS / PD); on this PLK110 build it carries the
     * vendor's integer code instead. Both forms are handled by `protocolFromSysfs`.
     */
    private val CHARGE_TECHNOLOGY_PATHS = listOf(
        "/sys/class/oplus_chg/battery/charge_technology"
    )

    /**
     * The original SuperVOOC flag. `voocchg_ing == 1` means the proprietary
     * OnePlus/OPPO rail is running — only the original charger can negotiate it, so it
     * is the "快充模式 == SVOOC" the user sees, and the only violet trigger.
     */
    private val VOOC_ING_PATH = "/sys/class/oplus_chg/battery/voocchg_ing"

    /**
     * The UFCS/PPS flag. `ppschg_ing == 4` is UFCS, `== 2` is PPS — the two public
     * standards third-party chargers speak.
     */
    private val PPS_ING_PATH = "/sys/class/oplus_chg/battery/ppschg_ing"

    /**
     * The negotiated contract's power ceiling — the number the 电池检测 app prints
     * inside the "快充模式" text as `UFCS(120.00W)`. It is the *contract* ceiling
     * (ppschg_power), not the instantaneous power: it stays at 120 W while the phone
     * only pulls a few watts, and it is what tells the original 120 W unit's UFCS
     * handshake from a third-party charger's downgraded UFCS. This is the single
     * place wattage is consulted, and only to separate the two UFCS tiers — never to
     * guess PPS/PD/hardware.
     */
    private val PPS_POWER_PATHS = listOf(
        "/sys/class/oplus_chg/battery/ppschg_power"
    )

    /**
     * Oplus sysfs node that carries the charge type as an integer (0 none, 2 normal,
     * 8 VOOC-basic, 14 SuperVOOC, 16 PD …). The app's secondary protocol source when
     * `charge_technology` cannot be read.
     */
    private val CHARGE_TYPE_PATHS = listOf(
        "/sys/class/power_supply/battery/charge_type",
        "/sys/class/power_supply/bms/charge_type"
    )

    /**
     * Active USB supply type (`[DCP]` / `[SDP]` / `[CDP]` / `[PD_PPS]` / `[BrickID]` …).
     * The bracketed value is the currently-negotiated link; the 电池检测 app reads it to
     * report "DCP" when the phone has dropped to a basic USB trickle.
     */
    private val USB_TYPE_PATH = "/sys/class/power_supply/usb/usb_type"

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
     * Resolves the charging protocol to exactly what the 电池检测 app shows as
     * "快充模式", and nothing else.
     *
     * The app's "快充模式" is `SVOOC` (the proprietary rail), `UFCS(120.00W)` (the
     * original charger's UFCS handshake at its 100 W+ contract), plain `UFCS` (a
     * third-party charger's downgraded UFCS), `PPS`, `PD` or `DCP`. The mapping is:
     *
     *  1. `voocchg_ing == 1`                  → SVOOC → violet
     *  2. `ppschg_ing == 4` (UFCS)            → UFCS, tiered by the contract ceiling
     *  3. `ppschg_ing == 2` (PPS)             → PPS → green
     *  4. `charge_technology` / `usb_type` PD → PD → blue
     *  5. DCP / plain USB                     → blue; nothing → orange
     *
     * The only wattage consulted is the *contract ceiling* (`ppschg_power`), and only
     * to split the two UFCS tiers — the original 120 W unit signs a 120 W contract, a
     * third-party UFCS charger signs far less. That number is literally the value
     * inside the "快充模式" text `UFCS(120.00W)`, so reading it is reading the text,
     * not guessing from instantaneous power or hardware. A third-party UFCS charger
     * never reaches the 100 W tier and stays green; the 酷态科 10 号 Ultra and the
     * 公牛 PD20W are therefore no longer miscoloured violet.
     */
    private fun protocolFromSysfs(): ChargeProtocol? {
        val voocIng = readSysfsInt(VOOC_ING_PATH) ?: 0
        val ppsIng = readSysfsInt(PPS_ING_PATH) ?: 0
        // 1) – 3) The proprietary rail and the public fast-charge standards. The UFCS
        //    tier is decided by the contract ceiling, the number the "快充模式" text
        //    itself prints as `UFCS(120.00W)`.
        classifyFastCharging(voocIng, ppsIng, maxContractWatts())?.let { return it }
        // 4) The protocol name the driver wrote out. A string carries the name
        //    outright; an integer carries the vendor's code.
        CHARGE_TECHNOLOGY_PATHS.firstNotNullOfOrNull { readSysfsLine(it) }?.let { tech ->
            val up = tech.uppercase()
            if (up.contains("SUPERVOOC") || up.contains("SVOOC") || up.contains("VOOC")) {
                return ChargeProtocol.SUPER_VOOC
            }
            if (up.contains("UFCS")) return ChargeProtocol.UFCS
            if (up.contains("PPS")) return ChargeProtocol.PPS
            if (up.contains("PD")) return ChargeProtocol.PD
            tech.toIntOrNull()?.let { return mapProtocolCode(it) }
        }
        // 5) The active USB supply type. A plain port (DCP/SDP/CDP) is what the app
        //    reports as "DCP" — the blue tone.
        if (isBasicUsb(readUsbTypeActive())) return ChargeProtocol.PD
        CHARGE_TYPE_PATHS.firstNotNullOfOrNull { readSysfsInt(it) }?.let { type ->
            if (type > 0) return mapProtocolCode(type)
        }
        return null
    }

    /**
     * Whether the negotiated contract ceiling marks the original charger.
     *
     * The `ppschg_power` node is the contract maximum the app prints as
     * `UFCS(120.00W)`. The original 120 W unit signs a 120 W contract; third-party
     * UFCS/PPS chargers sign far less (a 酷态科 10 号 Ultra UFCS runs at roughly PPS
     * levels, well under 100 W). The 100 W threshold is therefore the "百瓦档位" the
     * user described — it is the text's own number, not an instantaneous power read.
     */
    private fun maxContractWatts(): Int {
        val raw = PPS_POWER_PATHS.firstNotNullOfOrNull { readSysfsLine(it) } ?: return 0
        return contractWatts(raw)
    }

    /**
     * Maps an Oplus `charge_technology` / `charge_type` integer code onto a protocol.
     * These are the vendor's internal codes; on this PLK110 build `charge_technology`
     * carries 14 for UFCS (matching `ppschg_ing == 4`), so the code is only a fallback
     * for the named flags above.
     */
    private fun mapProtocolCode(code: Int): ChargeProtocol = when (code) {
        8 -> ChargeProtocol.SUPER_VOOC  // VOOC-basic — the proprietary OnePlus/OPPO rail
        16 -> ChargeProtocol.PD         // PD — plain USB Power Delivery (blue)
        2 -> ChargeProtocol.SLOW        // normal 5V — plain charger (orange)
        else -> ChargeProtocol.FAST     // any other fast code
    }

    /**
     * The kernel's `usb_type` node lists every supply type the port supports, with the
     * *active* ones wrapped in `[brackets]`. During a basic trickle charge it looks like
     * `[Unknown] SDP [DCP] …` (or `[DCP] …` on other firmwares). We collect all bracketed
     * tokens and, if any of them is a plain port (DCP/SDP/CDP), report that — this is the
     * signal the 电池检测 app uses to show "DCP" / "专用充电器" for a basic charge. Falling
     * back to the whole raw line keeps us compatible with firmwares that bracket a single
     * active type (e.g. `[Unknown]` at rest).
     */
    private fun readUsbTypeActive(): String? {
        val raw = readSysfsLine(USB_TYPE_PATH) ?: return null
        val active = Regex("\\[([^\\]]+)]").findAll(raw)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val candidates = if (active.isNotEmpty()) active else listOf(raw.trim())
        return (candidates.firstOrNull { isBasicUsb(it) } ?: candidates.first()).uppercase()
    }

    /** Whether the active USB link is a plain port with no fast-charge protocol. */
    private fun isBasicUsb(type: String?): Boolean {
        if (type == null) return false
        return type.contains("DCP") || type.contains("SDP") || type.contains("CDP")
    }

    private fun applyProtocol() {
        val charging = current.batteryPhase in setOf(BatteryPhase.CHARGING, BatteryPhase.FULL)
        // The Oplus driver's "快充模式" text is the authoritative protocol; only fall
        // back to the broadcast heuristic when it is unavailable (non-Oplus firmware)
        // or the charger is idle (the node can hold a stale value).
        val sysfs = if (charging) protocolFromSysfs() else null
        val protocol = sysfs ?: detectProtocol()
        if (protocol != current.chargeProtocol || oplusWatts != current.chargeWatts ||
            sysfs != lastSysfsProtocol
        ) {
            val tech = CHARGE_TECHNOLOGY_PATHS.firstNotNullOfOrNull { readSysfsLine(it) } ?: "n/a"
            val type = CHARGE_TYPE_PATHS.firstNotNullOfOrNull { readSysfsLine(it) } ?: "n/a"
            val usbType = readUsbTypeActive() ?: "n/a"
            log(Log.INFO, "3IN1",
                "charge protocol=$protocol (sysfs=$sysfs node=[tech=$tech type=$type usb=$usbType]) " +
                    "negotiated=${negotiatedVoltageMv}mV/${negotiatedCurrentMa}mA " +
                    "oplus=[fastType=$oplusFastType ppsMode=$oplusPpsMode tech=$oplusChargeTechnology " +
                    "plugged=$oplusChargePlugged fast=$oplusFastCharger v=${oplusChargerVoltageMv}mV w=${oplusWatts}] " +
                    "battery=[fastChgType=$batteryFastChgType chgTech=$chargerTechnologyLevel " +
                    "ppsIng=$ppsChgIng maxV=${negotiatedVoltageMv}mV]"
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
        }.onFailure { log(Log.INFO, "3IN1", "oplus charge broadcast unavailable: $it") }

        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            netCallback, handler
        )

        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
            ?.let {
                current = BatterySnapshot.apply(current, it)
                refreshChargerTelemetry()
                log(Log.INFO, "3IN1", "sticky battery level=${current.battery} observed=${current.batteryObserved}")
            }
            ?: log(Log.INFO, "3IN1", "no sticky battery broadcast received")
        current = current.copy(powerSave = power.isPowerSaveMode, airplane = isAirplane())

        registerSignalListeners()
        runCatching { subs.addOnSubscriptionsChangedListener(executor, subListener) }
            .onFailure { log(Log.INFO, "3IN1", "subscription listener unavailable: $it") }
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
            .onFailure { log(Log.INFO, "3IN1", "signal listener for sub $id unavailable: $it") }
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
            log(Log.INFO, "3IN1", "sims=$sig defaultSub=$defaultSub")
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

        /**
         * The contract ceiling that marks the original charger's UFCS handshake.
         *
         * The original 120 W unit signs a 120 W contract, so the "快充模式" text reads
         * `UFCS(120.00W)`. A third-party UFCS charger — the 酷态科 10 号 Ultra runs at
         * roughly PPS levels — signs far less. 100 W is the "百瓦档位" boundary the
         * user described.
         */
        const val ORIGINAL_CHARGER_WATTS = 100

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
         * The charge contract's power ceiling in watts, from a raw `ppschg_power` value.
         *
         * The node carries the number in an unspecified unit. The 电池检测 app
         * normalises it the same way: above 200000 the value is microwatts, above 1000
         * milliwatts, anything smaller is already watts.
         */
        fun contractWatts(raw: String?): Int {
            val value = raw?.trim()?.toDoubleOrNull() ?: return 0
            return when {
                value > 200000 -> (value / 1000000.0).toInt()
                value > 1000 -> (value / 1000.0).toInt()
                else -> value.toInt()
            }
        }

        /**
         * Classifies the public fast-charge standards from the driver's flags.
         *
         * This is the pure, testable core of `protocolFromSysfs`:
         *
         *   - `voocIng == 1`  → the proprietary rail (SVOOC) → violet, unconditionally
         *   - `ppsIng == 4`   → UFCS, tiered by the contract ceiling: the original
         *                       charger's 100 W+ handshake is violet, a third-party
         *                       downgrade is green
         *   - `ppsIng == 2`   → PPS → green
         *   - anything else    → null (fall through to the protocol-name / USB checks)
         *
         * The contract ceiling is the only wattage consulted, and only for the UFCS
         * tier — it is the number the "快充模式" text itself prints as `UFCS(120.00W)`,
         * so a third-party UFCS charger (酷态科 10 号 Ultra) never reaches it and stays
         * green.
         */
        fun classifyFastCharging(
            voocIng: Int,
            ppsIng: Int,
            contractWatts: Int
        ): ChargeProtocol? {
            if (voocIng == 1) return ChargeProtocol.SUPER_VOOC
            return when (ppsIng) {
                4 -> if (contractWatts >= ORIGINAL_CHARGER_WATTS) ChargeProtocol.SUPER_VOOC
                     else ChargeProtocol.UFCS
                2 -> ChargeProtocol.PPS
                else -> null
            }
        }

        /**
         * Maps the Oplus broadcast telemetry onto a protocol, used only when the
         * sysfs "快充模式" text is unavailable (non-Oplus firmware).
         *
         * This is the same protocol-name classification, not a power/voltage guess:
         * the broadcast `fast_chg_type` code names the negotiated protocol (3=VOOC,
         * 4=PPS, 6=UFCS) and nothing else is read. No wattage, no voltage, no
         * charger-technology level is consulted — a protocol is never promoted to the
         * violet rail by power alone, which is what miscoloured the 酷态科 and 公牛
         * chargers.
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
            // neutral — the driver node can hold a stale value while idle.
            if (!charging) return ChargeProtocol.NONE
            // The negotiated protocol code, in the app's priority order. These name
            // the protocol only; they say nothing about who made the charger.
            when (chargeTech) {
                FAST_CHG_TYPE_VOOC -> return ChargeProtocol.SUPER_VOOC
                FAST_CHG_TYPE_UFCS -> return ChargeProtocol.UFCS
                FAST_CHG_TYPE_PPS -> return ChargeProtocol.PPS
            }
            // The broadcast `fast_chg_type` carries the same code on some builds.
            when (fastType) {
                FAST_CHG_TYPE_VOOC -> return ChargeProtocol.SUPER_VOOC
                FAST_CHG_TYPE_UFCS -> return ChargeProtocol.UFCS
                FAST_CHG_TYPE_PPS -> return ChargeProtocol.PPS
            }
            // A fast charger the firmware flagged but named no rail for.
            if (fastCharger) return ChargeProtocol.FAST
            // Plugged in but no fast protocol reported: a plain USB charger.
            if (plugged > 0) return ChargeProtocol.SLOW
            return ChargeProtocol.NONE
        }

        const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
        const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"
    }
}
