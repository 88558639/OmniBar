package io.github.threeinone.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.threeinone.R
import io.github.threeinone.config.IndicatorConfig
import io.github.threeinone.model.IndicatorState
import io.github.threeinone.render.IndicatorView

/**
 * Foreground service that floats [IndicatorView] over the status-bar region using
 * WindowManager.TYPE_APPLICATION_OVERLAY. State is fed by [OverlayStateSource]
 * (public APIs only), so it is independent of SystemUI internals and survives OEM
 * / Android-version changes. This replaces the old Xposed + SystemUI hook path.
 */
class OverlayService : Service() {
    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private var keyguard: KeyguardManager? = null
    private lateinit var source: OverlayStateSource
    private val handler = Handler(Looper.getMainLooper())

    private var view: IndicatorView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(IndicatorConfig.FILE, MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        keyguard = getSystemService(KeyguardManager::class.java)
        source = OverlayStateSource(this) { state -> handler.post { view?.state = state } }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (readConfig().enabled) showOverlay() else hideOverlay()
        source.start()
        reportStatus("叠加层服务运行中")
        return START_STICKY
    }

    override fun onDestroy() {
        source.stop()
        hideOverlay()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun readConfig() = IndicatorConfig.read(prefs)

    private fun density() = resources.displayMetrics.density

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id > 0) resources.getDimensionPixelSize(id) else 0
        return if (h > 0) h else (24 * density()).toInt()
    }

    private fun makeParams(cfg: IndicatorConfig): WindowManager.LayoutParams {
        val size = (cfg.size * density()).toInt()
        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.END
            width = size
            height = size
            x = ((8 + cfg.horizontalPadding) * density()).toInt()
            y = (statusBarHeight() + cfg.verticalOffset * density()).toInt()
        }
    }

    private fun showOverlay() {
        if (view != null) return
        val cfg = readConfig()
        val v = IndicatorView(this).apply {
            tint = if (isNight()) Color.WHITE else Color.BLACK
            state = IndicatorState.demo
            config = cfg
        }
        val p = makeParams(cfg)
        view = v
        params = p
        runCatching { windowManager.addView(v, p) }
        applyVisibility()
        reportStatus("叠加层已显示")
    }

    private fun hideOverlay() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        params = null
        reportStatus("叠加层已隐藏")
    }

    private fun relayout() {
        val v = view ?: return
        val p = params ?: return
        val cfg = readConfig()
        val size = (cfg.size * density()).toInt()
        p.width = size
        p.height = size
        p.x = ((8 + cfg.horizontalPadding) * density()).toInt()
        p.y = (statusBarHeight() + cfg.verticalOffset * density()).toInt()
        v.config = cfg
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    private fun applyVisibility() {
        val cfg = readConfig()
        val locked = keyguard?.isKeyguardLocked == true
        val visible = cfg.enabled && (!locked || cfg.lockscreen)
        view?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun isNight(): Boolean {
        val ui = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun buildNotification(): Notification {
        val channelId = "threeinone_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId, "3IN1 状态栏叠加层", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "常驻以在状态栏显示电量/信号指示" }
        nm.createNotificationChannel(channel)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = if (launch != null) {
            android.app.PendingIntent.getActivity(
                this, 0, launch,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null
        return Notification.Builder(this, channelId)
            .setContentTitle("3IN1 状态栏叠加层")
            .setContentText("正在显示电量与信号指示")
            .setSmallIcon(R.drawable.app_logo_monochrome)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun reportStatus(text: String) {
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().apply {
            putString("status", text)
            putLong("time", System.currentTimeMillis())
            putBoolean("running", readConfig().enabled && view != null)
            apply()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        handler.post {
            when {
                key == "enabled" -> if (readConfig().enabled) showOverlay() else hideOverlay()
                view != null -> relayout()
            }
            applyVisibility()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT ->
                    handler.post { applyVisibility() }
            }
        }
    }

    companion object {
        const val NOTIF_ID = 1001

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
