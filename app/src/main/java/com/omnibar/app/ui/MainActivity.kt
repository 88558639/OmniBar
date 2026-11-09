package com.omnibar.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import com.omnibar.app.R
import com.omnibar.app.config.IndicatorConfig
import com.omnibar.app.config.PreferenceMigration
import com.omnibar.app.model.IndicatorState
import com.omnibar.app.render.IndicatorView
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The settings screen.
 *
 * The layout is built in code — the app ships without a third-party UI dependency — on
 * top of a small design system ([Palette], [CardSwitch]). The screen is a column of
 * cards: a live status card on top, then one card per group of settings.
 */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var palette: Palette
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var small: IndicatorView
    private lateinit var large: IndicatorView
    private lateinit var statusDot: StatusDot
    private lateinit var statusLabel: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var previewIndex = 0
    private var lastProbeToken: String? = null

    private val sync = Runnable {
        val revision = System.currentTimeMillis()
        writer.execute {
            val success = prefs.edit().putLong("revision", revision).commit()
            handler.post {
                if (isDestroyed) return@post
                // The status card stays quiet about saving; a failure is worth a
                // toast, a success is not worth any text at all.
                if (success) probe()
                else Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        // MODE_WORLD_READABLE was removed in Android 10 and now throws, so the modern
        // cross-process provider bridge is the only supported way to publish the settings.
        prefs = getSharedPreferences(IndicatorConfig.FILE, MODE_PRIVATE)
        PreferenceMigration.importOnce(prefs,
            java.io.File(applicationInfo.dataDir, "shared_prefs/${IndicatorConfig.FILE}.xml"))
        val normalized = IndicatorConfig.normalizeNumberMode(prefs)
        // The switches are gone: whether the stock icons are replaced and how the module
        // colours them now follow the module itself, so the app publishes them as
        // enabled. The only way to stop the replacement is to disable the module in
        // LSPosed.
        val published = !prefs.getBoolean("enabled", false) ||
            !prefs.getBoolean("colors", true) ||
            !prefs.getBoolean("chargeColorAuto", true)
        if (published) {
            prefs.edit()
                .putBoolean("enabled", true)
                .putBoolean("colors", true)
                .putBoolean("chargeColorAuto", true)
                .commit()
        }
        // The preview state can be picked from the intent — handy for screenshots and
        // for tests that need one specific state — and it survives a configuration
        // change in the saved instance state.
        val requested = intent?.getIntExtra("preview", -1) ?: -1
        previewIndex = (if (requested >= 0) requested else bundle?.getInt("preview", 0) ?: 0)
            .coerceIn(IndicatorState.previews.indices)
        buildPage()
        if (normalized || published) scheduleSave()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("preview", previewIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        probe()
    }

    override fun onDestroy() {
        if (handler.hasCallbacks(sync)) {
            handler.removeCallbacks(sync)
            sync.run()
        }
        handler.removeCallbacksAndMessages(null)
        writer.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ page

    private fun buildPage() {
        palette = Palette(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        root.addView(buildTopBar())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        buildHero()
        buildGroups()
        buildFooter()
        updatePreview()
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = rounded(palette.surface, 12f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })

        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("OmniBar", 22f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titles.addView(label("状态栏多合一 · ColorOS 17", 12f, palette.muted))
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        return bar.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(62))
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    /** The live status card: whether the module is running, plus the two previews. */
    private fun buildHero() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.surface, 20f)
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2))

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusDot = StatusDot(this)
        head.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        statusLabel = label("正在连接 SystemUI…", 14f, palette.ink).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        head.addView(statusLabel, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head, LinearLayout.LayoutParams(-1, -2))

        // There is no master switch any more: the replacement follows the module, so
        // the only way back to the stock icons is LSPosed.
        card.addView(label("如需恢复系统默认图标，请至LSPosed关闭并重启手机", 12f, palette.muted).apply {
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2))

        // The two previews: the enlarged one on a dark backdrop, the real-size one on a
        // light one, so both the shape and the true footprint are visible.
        val previews = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(12))
        }
        val enlarged = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(palette.previewBackdrop, 16f)
        }
        large = IndicatorView(this).apply { tint = Color.WHITE; alignInStatusBar = false }
        enlarged.addView(large, LinearLayout.LayoutParams(dp(84), dp(96)))
        enlarged.addView(label("放大预览", 12f, palette.muted).apply { gravity = Gravity.CENTER })
        previews.addView(enlarged, LinearLayout.LayoutParams(0, dp(132), 1.15f).apply { marginEnd = dp(10) })

        val natural = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(palette.surfaceAlt, 16f)
        }
        small = IndicatorView(this).apply { tint = palette.ink; alignInStatusBar = false }
        natural.addView(small, LinearLayout.LayoutParams(dp(24), dp(30)))
        natural.addView(label("实际尺寸", 12f, palette.muted).apply { gravity = Gravity.CENTER })
        previews.addView(natural, LinearLayout.LayoutParams(0, dp(132), 1f))
        card.addView(previews, LinearLayout.LayoutParams(-1, -2))

        card.addView(label("预览状态", 12f, palette.muted).apply { setPadding(0, 0, 0, dp(8)) })
        val chips = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        IndicatorState.previews.forEachIndexed { index, (name, _) ->
            chipRow.addView(chip(name, index == previewIndex, palette).apply {
                setOnClickListener {
                    previewIndex = index
                    for (i in 0 until chipRow.childCount) {
                        val child = chipRow.getChildAt(i) as TextView
                        val on = i == index
                        child.setTextColor(if (on) palette.onAccent else palette.muted)
                        child.background = if (on) pill(palette.accent) else pill(palette.surfaceAlt, palette.stroke)
                    }
                    updatePreview()
                }
            }, LinearLayout.LayoutParams(-2, dp(34)).apply {
                marginEnd = dp(8)
                topMargin = dp(4)
            })
        }
        chips.addView(chipRow, LinearLayout.LayoutParams(-2, dp(42)))
        card.addView(chips, LinearLayout.LayoutParams(-1, dp(42)))
    }

    private fun buildGroups() {
        section("状态颜色").apply {
            textRow("启用状态颜色", "按电量与省电状态自动着色，与状态栏实时显示一致")
            colorRow("低电量", "lowColor", 0xFFE7433A.toInt())
            colorRow("严重低电量", "criticalColor", 0xFFD50000.toInt())
            colorRow("省电模式", "saverColor", 0xFFFFB300.toInt())
            hint("判定顺序：\n" +
                "1. 充电中或已充满用充电配色\n" +
                "2. 电量 ≤ 10% 用「严重低电量」色\n" +
                "3. ≤ 20% 用「低电量」色\n" +
                "4. 开启系统省电模式用「省电模式」色\n" +
                "使用说明：\n" +
                "1. 点击整行（包括左侧色块与右侧色值）即可修改颜色\n" +
                "2. 为保证对比度，状态栏里的实际颜色会按背景做少量校正")
        }

        section("充电配色").apply {
            textRow("按充电协议自动配色", null)
            colorRow("原装充电器 · 紫", "voocColor", 0xFF9C6BFF.toInt())
            colorRow("第三方 UFCS · 绿", "ufcsColor", 0xFF2ECC71.toInt())
            colorRow("PD / DCP / PPS · 蓝", "ppsColor", 0xFF3B82F6.toInt())
            colorRow("普通 / 慢充 · 橙", "unknownColor", 0xFFF97316.toInt())
            hint("自动模式严格按「快充模式」上色：\n" +
                "1. 快充模式为 SVOOC，或原装百瓦 UFCS 档位，紫\n" +
                "2. 快充模式为第三方 UFCS，绿\n" +
                "3. 快充模式为 PD、DCP、PPS，蓝\n" +
                "4. 快充模式为普通、慢充，橙\n" +
                "说明：\n" +
                "1. 原装百瓦 UFCS 依据合同功率上限 100W 判断\n" +
                "2. 第三方充电器绝不因功率高而显示紫色\n" +
                "3. 充满后系统停止充电、快充协议归零，图标会从充电色短暂跳橙，随后恢复跟随系统色，属正常")
        }
    }

    private fun buildFooter() {
        content.addView(
            Button(this).apply {
                text = "更新日志"
                textSize = 15f
                setTextColor(palette.ink)
                background = rounded(palette.surface, 18f)
                isAllCaps = false
                elevation = 0f
                stateListAnimator = null
                setOnClickListener { showChangelog() }
            },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) }
        )
    }

    /** The changelog sheet, styled to match the app instead of the stock alert dialog. */
    private fun showChangelog() {
        val dialog = Dialog(this).apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0.45f)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.surface, 24f)
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }
        // Title in the brand accent, with no close button — tap outside to dismiss.
        panel.addView(
            label("更新日志", 18f, palette.accent).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        )
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = label(CHANGELOG, 14f, palette.ink).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(14), 0, 0)
        }
        scroll.addView(body)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(panel)
        val metrics = resources.displayMetrics
        val maxW = (metrics.widthPixels * 0.86f).toInt()
        val maxH = (metrics.heightPixels * 0.72f).toInt()
        dialog.show()
        dialog.window?.setLayout(maxW, maxH)
    }

    /** 最近 10 次更新日志。 */
    private val CHANGELOG = """
        2026.11.09pm00
        修复更换充电器或充电线时颜色短暂显示为错误协议色

        2026.11.09
        新增 WiFi 代数显示（WiFi 图标右下角标 5/6/7/8）
        圈内元素整体上移，外圈不变
        「关于」改为「更新日志」
        修复第三方 UFCS 充电器误判为橙色
        修复 PPS 充电器误判为 UFCS 显示绿色
        修复 PD 充电器误判为普通充电显示橙色
        修复原厂 SVOOC 充电器误判为蓝色

        2026.11.05
        优化状态栏指示器渲染细节

        2026.11.04
        界面换成蓝色风格，去掉滚动条

        2026.11.03
        修复「关于」按钮阴影显示异常

        2026.11.02
        模块正式定名 OmniBar

        2026.11.01
        迁移到 libxposed API 102

        2026.10.31
        修复真机图标溢出问题

        2026.10.30
        重新设计应用图标

        2026.10.29
        修复模块加载时参数不可变导致的崩溃
    """.trimIndent()

    // ------------------------------------------------------------------ building blocks

    /** A section heading followed by an empty card; the rows are added to the card. */
    private fun section(title: String): LinearLayout {
        content.addView(label(title, 14f, palette.muted).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), dp(0), dp(4), 0)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.surface, 20f)
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return card
    }

    /** An explanatory line inside a card. */
    private fun LinearLayout.hint(text: String) {
        addView(
            Label(this@MainActivity, text, 12.5f, palette.muted).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(10), 0, dp(8))
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    /** A hairline between two rows of a card. */
    private fun LinearLayout.rowSeparator() {
        if (childCount == 0) return
        addView(
            View(this@MainActivity).apply { setBackgroundColor(palette.stroke) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(2); bottomMargin = dp(2) }
        )
    }

    /**
     * A row that only states what the module does.
     *
     * The switches are gone from the page, so the rows that used to carry one stay
     * behind as plain text.
     */
    private fun LinearLayout.textRow(title: String, subtitle: String?) {
        rowSeparator()
        val row = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15.5f))
        if (subtitle != null) {
            texts.addView(label(subtitle, 12f, palette.muted).apply {
                setPadding(0, dp(3), dp(12), 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun LinearLayout.colorRow(title: String, key: String, default: Int) {
        rowSeparator()
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "$title 颜色"
        }
        val dot = ColorDot(this@MainActivity).apply { color = prefs.getInt(key, default) }
        row.addView(dot, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
        row.addView(label(title, 15.5f), LinearLayout.LayoutParams(0, -2, 1f))
        val hexLabel = Label(this@MainActivity, hex(prefs.getInt(key, default)), 12.5f, palette.muted).apply {
            typeface = Typeface.MONOSPACE
        }
        row.addView(hexLabel)
        row.setOnClickListener {
            showColorDialog(title, key, default) { color ->
                dot.color = color
                hexLabel.text = hex(color)
            }
        }
        addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showColorDialog(title: String, key: String, default: Int, onPicked: (Int) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine()
            setText(hex(prefs.getInt(key, default)))
            selectAll()
            contentDescription = "十六进制颜色"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(12))
            addView(input)
        }
        val paletteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        listOf(
            0xFF9C6BFF.toInt(), 0xFF2ECC71.toInt(), 0xFF3B82F6.toInt(), 0xFFF97316.toInt(),
            0xFF1CB753.toInt(), 0xFFE7433A.toInt(), 0xFFFFB300.toInt(), 0xFF007F70.toInt()
        ).forEach { entry ->
            paletteRow.addView(ColorDot(this).apply {
                color = entry
                contentDescription = hex(entry)
                isClickable = true
                isFocusable = true
                setOnClickListener { input.setText(hex(entry)) }
            }, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        holder.addView(paletteRow)

        val dialog = AlertDialog.Builder(this).setTitle(title).setView(holder)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString().trim()
                if (!Regex("#[0-9a-fA-F]{6}").matches(raw)) input.error = "请输入 #RRGGBB"
                else {
                    val next = Color.parseColor(raw)
                    prefs.edit().putInt(key, next).apply()
                    onPicked(next)
                    scheduleSave(); dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ state

    private fun updatePreview() {
        if (!::small.isInitialized) return
        val c = IndicatorConfig.read(prefs)
        small.config = c; large.config = c
        val state = IndicatorState.previews[previewIndex].second
        small.state = state; large.state = state
        small.layoutParams = LinearLayout.LayoutParams(dp(c.size), dp(c.size)).apply {
            gravity = Gravity.CENTER
        }
        small.translationY = dp(c.verticalOffset).toFloat()
    }

    private fun scheduleSave() {
        updatePreview()
        handler.removeCallbacks(sync); handler.postDelayed(sync, 180)
    }

    private fun probe() {
        val token = UUID.randomUUID().toString()
        lastProbeToken = token
        sendBroadcast(Intent(IndicatorConfig.REFRESH)
            .setPackage("com.android.systemui")
            .putExtra(IndicatorConfig.PROBE_TOKEN, token))
        handler.postDelayed({ if (!isDestroyed) updateDiagnostics() }, 650)
        handler.postDelayed({ if (!isDestroyed) updateDiagnostics() }, 2000)
    }

    /**
     * Mirrors the module's report on the status card.
     *
     * The page carries no diagnostics panel any more, so the one line in the status
     * card is the only thing that is updated here.
     */
    private fun updateDiagnostics() {
        val p = getSharedPreferences("diagnostics", MODE_PRIVATE)
        // The provider keeps the latest receipt under the probe keys; the plain
        // "status"/"time" keys are a leftover of the pre-probe protocol and may
        // still hold a fault from days ago. Never show those as if they were
        // the current state.
        val probeStatus = p.getString(IndicatorConfig.PROBE_STATUS, "") ?: ""
        val legacyStatus = p.getString("status", "") ?: ""
        val matchedProbe = lastProbeToken != null &&
            p.getString(IndicatorConfig.PROBE_TOKEN, null) == lastProbeToken
        val status = when {
            matchedProbe || probeStatus.isNotEmpty() -> probeStatus
            else -> legacyStatus
        }
        statusLabel.text = status.ifEmpty { "模块未启用" }
        statusDot.color = when {
            // The module reports "模块已运行"; the older wording still shows up while
            // SystemUI runs a previous build of the module.
            status.contains("模块已运行") || status.contains("替换已运行") -> palette.accent
            status.contains("回退") -> palette.danger
            status.isEmpty() -> palette.muted
            else -> palette.surfaceAlt
        }
    }

    // ------------------------------------------------------------------ small helpers

    private fun label(text: String, size: Float, color: Int = palette.ink) =
        Label(this, text, size, color)

    private fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = kotlin.math.round(value * resources.displayMetrics.density).toInt()
}

/**
 * A plain [TextView] that carries the app's typography defaults.
 *
 * The platform's `TextView` cannot take the line spacing and colour in one constructor
 * call, and the settings screen sets both on almost every label.
 */
internal class Label(context: Context, text: String, size: Float, color: Int) : TextView(context) {
    init {
        this.text = text
        textSize = size
        setTextColor(color)
    }
}
