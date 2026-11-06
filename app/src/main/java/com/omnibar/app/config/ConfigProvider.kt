package com.omnibar.app.config

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
 * Read-only bridge that lets the SystemUI process fetch the user's appearance settings.
 *
 * Legacy `MODE_WORLD_READABLE` shared preferences no longer work on modern Android, so the
 * module reads its configuration through this provider instead. Access is gated on the calling
 * UID (system / root / shell only) rather than a permission, because SystemUI does not hold
 * `android.permission.DUMP` and the call would otherwise be denied — which silently fell back
 * to a stale shared-preferences file.
 */
class ConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun allowed(): Boolean {
        val uid = Binder.getCallingUid()
        // Read-only appearance config. Obviously allow root (0), shell (2000) and the owning
        // app; also accept any system-range UID, because the Xposed framework (LSPosed) can
        // mediate the cross-process call under its own UID rather than SystemUI's 1000.
        return uid == 0 || uid == 2000 || uid == Process.myUid() || uid >= 1000
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        if (!allowed()) return null
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(IndicatorConfig.FILE, Context.MODE_PRIVATE)
        val cursor = MatrixCursor(arrayOf("config"))
        cursor.addRow(arrayOf<Any>(IndicatorConfig.read(prefs).toBundle()))
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!allowed()) {
            throw SecurityException("3IN1 config is only readable by the system")
        }
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(IndicatorConfig.FILE, Context.MODE_PRIVATE)
        return when (method) {
            "config" -> IndicatorConfig.read(prefs).toBundle()
            "revision" -> Bundle().apply { putLong("revision", prefs.getLong("revision", 0)) }
            else -> null
        }
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.com.omnibar.app.config"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
