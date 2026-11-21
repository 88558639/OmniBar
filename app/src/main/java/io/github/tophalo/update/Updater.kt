package io.github.tophalo.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * In-app update.
 *
 * Checks GitHub Releases for a newer APK, downloads it through a mirror-accelerated list
 * (direct GitHub is the last resort) and hands the file to the system installer. The whole
 * thing is dependency-free: JSON via [org.json], HTTP via [HttpURLConnection].
 */
object Updater {
    private const val TAG = "TopHalo"
    const val OWNER = "88558639"
    const val REPO = "TopHalo"

    /**
     * Download mirrors, tried in order. Each entry is a prefix prepended to the raw GitHub
     * URL; an empty entry means a direct GitHub download. These are third-party proxies and
     * may go stale — edit the list when one stops working.
     */
    val MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        ""
    )

    data class UpdateInfo(val version: String, val apkUrl: String, val body: String)

    /** Set by [check] on a network / parse failure, so the manual path can report it. */
    @Volatile
    var lastError: String? = null
        private set

    /** Set to true by the UI to abort an in-flight download. */
    @Volatile
    var cancelled = false

    /**
     * Returns the latest release when it is newer than the installed build, null otherwise.
     * A network / parse failure also returns null (and records [lastError]).
     */
    fun check(context: Context): UpdateInfo? {
        val latest = fetchLatest() ?: return null
        val installed = installedVersion(context)
        val newer = versionKey(latest.version) > versionKey(installed)
        Log.i(TAG, "update check: installed=$installed latest=${latest.version} newer=$newer")
        return if (newer) latest else null
    }

    private fun fetchLatest(): UpdateInfo? {
        // 直连优先，被劫持/超时时走加速镜像兜底（与下载逻辑一致）。
        val urls = listOf(
            "https://api.github.com/repos/$OWNER/$REPO/releases/latest",
            "https://gh-proxy.com/https://api.github.com/repos/$OWNER/$REPO/releases/latest",
        )
        for (url in urls) {
            val info = fetchLatestFrom(url)
            if (info != null) return info
            Log.i(TAG, "update check fallback: $url -> $lastError")
        }
        return null
    }

    private fun fetchLatestFrom(url: String): UpdateInfo? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TopHalo-Updater")
            }
            try {
                if (conn.responseCode != 200) {
                    lastError = "HTTP ${conn.responseCode}"
                    null
                } else {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    val tag = json.getString("tag_name").removePrefix("v").removePrefix("V")
                    val body = json.optString("body").orEmpty()
                    val assets = json.getJSONArray("assets")
                    var apkUrl: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) {
                            apkUrl = a.getString("browser_download_url")
                            break
                        }
                    }
                    lastError = null
                    if (apkUrl != null) UpdateInfo(tag, apkUrl, body) else null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            lastError = e.message
            null
        }
    }

    /** Downloads through the mirror list, reporting 0..100 progress. Returns true on success. */
    fun download(info: UpdateInfo, outFile: File, onProgress: (Int) -> Unit): Boolean {
        cancelled = false
        outFile.parentFile?.mkdirs()
        for (mirror in MIRRORS) {
            if (cancelled) return false
            val url = if (mirror.isEmpty()) info.apkUrl else mirror + info.apkUrl
            Log.i(TAG, "update download try: $url")
            if (downloadSingle(url, outFile, onProgress)) return true
            if (cancelled) return false
        }
        return false
    }

    private fun downloadSingle(url: String, outFile: File, onProgress: (Int) -> Unit): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TopHalo-Updater")
            }
            try {
                if (conn.responseCode != 200) {
                    false
                } else {
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        outFile.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            var got = 0L
                            var last = -1
                            while (true) {
                                if (cancelled) return false
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                got += n
                                if (total > 0) {
                                    val pct = ((got * 100) / total).toInt()
                                    if (pct != last) {
                                        last = pct
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                    true
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Hands the downloaded APK to the system installer via [ApkProvider]. */
    fun install(context: Context) {
        val uri = Uri.parse("content://${context.packageName}.apk/update.apk")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** Whether the app may install unknown apps (always true below Android 8). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" permission page for this app. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
        context.startActivity(intent)
    }

    /** Auto-check is limited to once per half-day: one in the morning, one in the afternoon. */
    fun shouldAutoCheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val slot = if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12) "am" else "pm"
        return prefs.getString("date", "") != today || prefs.getString("slot", "") != slot
    }

    fun markAutoChecked(context: Context) {
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val slot = if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12) "am" else "pm"
        prefs.edit().putString("date", today).putString("slot", slot).apply()
    }

    private fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * Best-effort cleanup: on app start, if the cached APK matches the currently installed
     * APK byte-for-byte, the download has already been installed, so delete the cache.
     */
    fun cleanupInstalledApk(context: Context) {
        val cached = File(context.cacheDir, "apk/update.apk")
        if (!cached.isFile) return
        try {
            val installed = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .applicationInfo?.sourceDir
            if (!installed.isNullOrEmpty() && sha256(File(installed)) == sha256(cached)) {
                cached.delete()
                Log.i(TAG, "cleaned installed apk cache")
            }
        } catch (e: Exception) {
            // leave the cache in place on any failure
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * Maps a version like "2026.11.14pm01" to a sortable key: "20261114" + "1"(pm)/"0"(am)
     * + "01". Unparseable versions map to "" so they always count as older.
     */
    private fun versionKey(v: String): String {
        val m = Regex("""(\d{4})\.(\d{1,2})\.(\d{1,2})(am|pm)(\d{1,2})""").find(v) ?: return ""
        val (y, mo, d, half, seq) = m.destructured
        val date = y + mo.padStart(2, '0') + d.padStart(2, '0')
        val halfKey = if (half.equals("pm", ignoreCase = true)) "1" else "0"
        return date + halfKey + seq.padStart(2, '0')
    }
}
