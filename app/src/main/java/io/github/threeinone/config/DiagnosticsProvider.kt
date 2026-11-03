package io.github.threeinone.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Receives the running-state report from the SystemUI-side module so the settings screen can
 * show whether the indicator is actually live (rather than silently failing inside SystemUI).
 * Access is gated on the calling UID, same as [ConfigProvider].
 */
class DiagnosticsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun allowed(): Boolean {
        val uid = Binder.getCallingUid()
        return uid == 0 || uid == 1000 || uid == 2000 || uid == Process.myUid()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!allowed()) {
            throw SecurityException("3IN1 diagnostics are only writable by the system")
        }
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        return when (method) {
            "report" -> {
                prefs.edit().apply {
                    extras?.getString("status")?.let { putString(IndicatorConfig.PROBE_STATUS, it) }
                    extras?.getLong("revision")?.let { putLong(IndicatorConfig.PROBE_REVISION, it) }
                    extras?.getString(IndicatorConfig.PROBE_TOKEN)
                        ?.let { putString(IndicatorConfig.PROBE_TOKEN, it) }
                    putLong(IndicatorConfig.PROBE_TIME, System.currentTimeMillis())
                }.apply()
                null
            }
            "status" -> Bundle().apply {
                putString(IndicatorConfig.PROBE_STATUS, prefs.getString(IndicatorConfig.PROBE_STATUS, ""))
                putLong(IndicatorConfig.PROBE_TIME, prefs.getLong(IndicatorConfig.PROBE_TIME, 0))
                putLong(IndicatorConfig.PROBE_REVISION, prefs.getLong(IndicatorConfig.PROBE_REVISION, -1))
                putString(IndicatorConfig.PROBE_TOKEN, prefs.getString(IndicatorConfig.PROBE_TOKEN, ""))
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        if (!allowed()) return null
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val cursor = MatrixCursor(arrayOf("status", "time", "revision"))
        cursor.addRow(
            arrayOf<Any>(
                prefs.getString(IndicatorConfig.PROBE_STATUS, "") ?: "",
                prefs.getLong(IndicatorConfig.PROBE_TIME, 0),
                prefs.getLong(IndicatorConfig.PROBE_REVISION, -1)
            )
        )
        return cursor
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.io.github.threeinone.diagnostics"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
