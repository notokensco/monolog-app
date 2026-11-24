package com.notokens.monolog.ime.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.util.UUID

/** Serves the item selected by the user to the editor holding the temporary read grant. */
class KeyboardMediaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String? = selected(uri)?.mime

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val item = selected(uri) ?: throw FileNotFoundException()
        if (mode != "r") throw FileNotFoundException()
        return openPipeHelper(uri, item.mime, null, item) { output, _, _, _, request ->
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                if (request != null) runCatching { KeyboardMedia.stream(request, stream) }
            }
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val item = selected(uri) ?: return null
        val columns = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns.toTypedArray()).apply {
            addRow(columns.map { column ->
                if (column == OpenableColumns.DISPLAY_NAME) "monolog-media.${item.extension}" else null
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private data class Grant(val item: KeyboardMedia.Item, val expires: Long)
        private val grants = linkedMapOf<String, Grant>()

        internal fun uriFor(context: Context, item: KeyboardMedia.Item): Uri {
            val id = UUID.randomUUID().toString()
            synchronized(grants) {
                val now = SystemClock.elapsedRealtime()
                grants.entries.removeAll { it.value.expires < now }
                while (grants.size >= 16) grants.remove(grants.keys.first())
                grants[id] = Grant(item, now + 5 * 60_000)
            }
            return Uri.Builder().scheme("content").authority("${context.packageName}.imemedia").appendPath(id).build()
        }

        private fun selected(uri: Uri): KeyboardMedia.Item? = synchronized(grants) {
            val key = uri.pathSegments.singleOrNull() ?: return@synchronized null
            val grant = grants[key] ?: return@synchronized null
            grant.item.takeIf { SystemClock.elapsedRealtime() <= grant.expires }
        }
    }
}
