package io.github.threeinone.model

enum class BatteryPhase { NORMAL, CHARGING, FULL, PAUSED, UNKNOWN }
enum class WifiPhase { OFF, DISCONNECTED, CONNECTING, CONNECTED, NO_INTERNET, CAPTIVE }
enum class SimPhase { READY, NO_SIGNAL, EMERGENCY, ABSENT, DISABLED, LOCKED, UNKNOWN }

/**
 * Detected charging protocol, classified by what the 电池检测 app shows in its
 * "快充模式" field — never by guessing the charger's hardware or power.
 *
 * [SUPER_VOOC] is the proprietary OnePlus/OPPO rail (SuperVOOC/VOOC). Only the
 * original charger can negotiate it, so it is the single reliable marker of an
 * original charger. [UFCS] and [PPS] are the public USB fast-charge standards that
 * third-party chargers speak. [PD] is the plain USB Power Delivery link (without the
 * PPS step). [SLOW] and [FAST] are the non-negotiated fallbacks. [NONE] means the
 * firmware did not report a "快充模式" at all.
 *
 * The colour follows the same text the app shows: SuperVOOC → violet, UFCS/PPS →
 * green, PD/DCP → blue, plain/slow → orange. A third-party charger whose "快充模式"
 * reads UFCS must stay green — the app cannot tell it apart from the original's UFCS
 * handshake, and guessing from wattage or voltage is what caused the 酷态科 / 公牛
 * miscolour bugs, so no protocol is ever promoted to violet by power alone.
 */
enum class ChargeProtocol { NONE, SLOW, FAST, PD, PPS, UFCS, SUPER_VOOC }

/**
 * Data-traffic activity, matching Android's [android.telephony.TelephonyManager] data-activity
 * constants (NONE=0, IN=1, OUT=2, INOUT=3) so reflection values can be mapped directly.
 */
enum class DataActivity(val bits: Int) {
    NONE(0), IN(1), OUT(2), BOTH(3);
    val hasIn get() = bits and 1 != 0   // receiving / download
    val hasOut get() = bits and 2 != 0  // transmitting / upload
}

/** A single SIM/cellular subscription's render-relevant state. */
data class IndicatorSim(
    val slot: Int = 0,
    val phase: SimPhase = SimPhase.UNKNOWN,
    val level: Int = 0,
    val roaming: Boolean = false,
    val dataEnabled: Boolean = false,
    val networkType: String = "",
    val dataDefault: Boolean = false,
    val activity: DataActivity = DataActivity.NONE
) {
    val dots get() = if (phase == SimPhase.READY) level.coerceIn(0, 4) else 0
}

data class IndicatorState(
    val battery: Int = -1,
    val batteryPhase: BatteryPhase = BatteryPhase.UNKNOWN,
    val powerSave: Boolean = false,
    val wifi: WifiPhase = WifiPhase.OFF,
    val wifiLevel: Int = 0,
    val sim: SimPhase = SimPhase.UNKNOWN,
    val mobileLevel: Int = 0,
    val networkType: String = "",
    val dataEnabled: Boolean = false,
    val roaming: Boolean = false,
    val airplane: Boolean = false,
    val vpn: Boolean = false,
    val networkReady: Boolean = false,
    val batteryObserved: Boolean = false,
    /** Charging protocol reported by the firmware (VOOC / PPS / plain fast / slow). */
    val chargeProtocol: ChargeProtocol = ChargeProtocol.NONE,
    /** Negotiated charging power in milliwatts, when the firmware reports it. */
    val chargeWatts: Int = 0,
    // --- SuperIcon port: dual-SIM + traffic activity (default off / unchanged rendering) ---
    val sims: List<IndicatorSim> = emptyList(),
    val wifiActivity: DataActivity = DataActivity.NONE,
    val mobileActivity: DataActivity = DataActivity.NONE
) {
    val wifiConnected get() = wifi in setOf(WifiPhase.CONNECTED, WifiPhase.NO_INTERNET, WifiPhase.CAPTIVE)
    val ready get() = networkReady && batteryObserved
    val progress get() = if (battery < 0) 0f else battery.coerceIn(0, 100) / 100f
    val dots get() = if (sim == SimPhase.READY && !airplane) mobileLevel.coerceIn(0, 4) else 0

    fun description(): String = buildString {
        append(if (battery < 0) "电量未知" else "电量 $battery%")
        append("，" + when (batteryPhase) {
            BatteryPhase.CHARGING -> "充电中"
            BatteryPhase.FULL -> "已充满"
            BatteryPhase.PAUSED -> "已插电，未充电"
            BatteryPhase.UNKNOWN -> "充放电状态未知"
            else -> if (powerSave) "省电模式" else "正常"
        })
        append("，Wi-Fi " + when (wifi) {
            WifiPhase.OFF -> "关闭"
            WifiPhase.DISCONNECTED -> "未连接"
            WifiPhase.CONNECTING -> "连接中"
            WifiPhase.CONNECTED -> "已连接，${wifiLevel.coerceIn(0, 4)}格"
            WifiPhase.NO_INTERNET -> "无互联网"
            WifiPhase.CAPTIVE -> "需要登录"
        })
        append("，" + if (airplane) "飞行模式" else when (sim) {
            SimPhase.READY -> "$networkType 信号 $dots 格" + if (dataEnabled) "" else "，移动数据关闭"
            SimPhase.NO_SIGNAL -> "无信号"
            SimPhase.EMERGENCY -> "仅紧急呼叫"
            SimPhase.ABSENT -> "无 SIM 卡"
            SimPhase.DISABLED -> "SIM 已停用"
            SimPhase.LOCKED -> "SIM 已锁定"
            SimPhase.UNKNOWN -> "蜂窝状态未知"
        })
        if (roaming) append("，漫游")
        if (vpn) append("，VPN")
        if (!airplane && mobileActivity != DataActivity.NONE) append(
            "，" + when {
                mobileActivity.hasIn && mobileActivity.hasOut -> "上下行"
                mobileActivity.hasIn -> "下行"
                else -> "上行"
            }
        )
        if (sims.size > 1) append("，双卡")
    }

    companion object {
        val demo = IndicatorState(76, BatteryPhase.NORMAL, wifi = WifiPhase.CONNECTED,
            wifiLevel = 4, sim = SimPhase.READY, mobileLevel = 4, networkType = "5G",
            dataEnabled = true, networkReady = true, batteryObserved = true)
        val previews: List<Pair<String, IndicatorState>> = listOf(
            "正常 · Wi-Fi" to demo,
            "充电中" to demo.copy(battery = 56, batteryPhase = BatteryPhase.CHARGING),
            "已充满" to demo.copy(battery = 100, batteryPhase = BatteryPhase.FULL),
            "低电量" to demo.copy(battery = 12),
            "严重低电量" to demo.copy(battery = 5),
            "省电模式" to demo.copy(battery = 45, powerSave = true),
            "插电未充电 / 限充" to demo.copy(battery = 80, batteryPhase = BatteryPhase.PAUSED),
            "Wi-Fi 弱信号" to demo.copy(wifiLevel = 1, mobileLevel = 1),
            "Wi-Fi 无互联网" to demo.copy(wifi = WifiPhase.NO_INTERNET),
            "Wi-Fi 需要登录" to demo.copy(wifi = WifiPhase.CAPTIVE),
            "Wi-Fi 连接中" to demo.copy(wifi = WifiPhase.CONNECTING),
            "无信号" to demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.NO_SIGNAL, mobileLevel = 0),
            "仅紧急呼叫" to demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.EMERGENCY),
            // A missing, a disabled and a locked SIM all read the same in the band — a
            // faint "sim" — so they share one preview instead of three near-identical
            // ones.
            "无 SIM 卡 · 停用 · 锁定" to demo.copy(wifi = WifiPhase.OFF, sim = SimPhase.ABSENT),
            "飞行模式" to demo.copy(wifi = WifiPhase.OFF, airplane = true),
            "VPN" to demo.copy(vpn = true),
            // The second card is a plain one: both columns keep the tint, so the
            // preview shows the ring instead of a colour demo.
            "双卡信号" to demo.copy(
                sims = listOf(
                    IndicatorSim(slot = 0, phase = SimPhase.READY, level = 4, dataDefault = true),
                    IndicatorSim(slot = 1, phase = SimPhase.READY, level = 2)
                )
            )
        )
        fun networkLabel(type: Int, overrideType: Int): String = when {
            type == 20 || overrideType in setOf(3, 4, 5) -> "5G"
            type in setOf(13, 19) || overrideType in setOf(1, 2) -> "4G"
            type in setOf(3, 5, 6, 8, 9, 10, 12, 14, 15, 17) -> "3G"
            type in setOf(1, 2, 4, 7, 11, 16) -> "2G"
            else -> ""
        }
    }
}
