package io.github.threeinone.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * Read-only bridge that lets the SystemUI process fetch the user's appearance settings.
 *
 * Legacy `MODE_WORLD_READABLE` shared preferences no longer work on modern Android, so the
 * module reads its configuration through this provider instead. It is protected by
 * `android.permission.DUMP`, which only privileged/system apps (SystemUI) can hold.
 */
class ConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(IndicatorConfig.FILE, Context.MODE_PRIVATE)
        val cursor = MatrixCursor(arrayOf("config"))
        cursor.addRow(arrayOf<Any>(IndicatorConfig.read(prefs).toBundle()))
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(IndicatorConfig.FILE, Context.MODE_PRIVATE)
        return when (method) {
            "config" -> IndicatorConfig.read(prefs).toBundle()
            "revision" -> Bundle().apply { putLong("revision", prefs.getLong("revision", 0)) }
            else -> null
        }
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.io.github.threeinone.config"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
