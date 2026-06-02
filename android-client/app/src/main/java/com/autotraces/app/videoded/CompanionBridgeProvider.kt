package com.autotraces.app.videoded

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class CompanionBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (URI_MATCHER.match(uri) != CODE_BINDING) return null
        val ctx = context ?: return null
        val port = ApiSettings.getListenPort(ctx)
        val base = ApiSettings.localBaseUrl(ctx)
        val token = ApiSettings.getToken(ctx)
        val cols = arrayOf(
            CompanionBridge.COLUMN_TOKEN,
            CompanionBridge.COLUMN_BASE_URL,
            CompanionBridge.COLUMN_LISTEN_PORT,
        )
        return MatrixCursor(cols).apply {
            addRow(arrayOf(token, base, port.toString()))
        }
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        CODE_BINDING -> "vnd.android.cursor.dir/vnd.com.autotraces.app.videoded.binding"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val CODE_BINDING = 1
        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(CompanionBridge.AUTHORITY, CompanionBridge.PATH_BINDING, CODE_BINDING)
        }
    }
}
