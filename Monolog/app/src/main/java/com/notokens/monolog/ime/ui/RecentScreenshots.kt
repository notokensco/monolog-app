package com.notokens.monolog.ime.ui

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val SCREENSHOT_LIFETIME_MS = 15 * 60 * 1000L

internal data class RecentScreenshot(val uri: Uri, val mime: String, val capturedAt: Long) {
    val expiresAt get() = capturedAt + SCREENSHOT_LIFETIME_MS
    fun accepts(types: List<String>) = types.any { ClipDescription.compareMimeTypes(mime, it) }
    fun isFresh(now: Long = System.currentTimeMillis()) = now in capturedAt until expiresAt
}

internal class RecentScreenshots(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("recent_screenshot_suggestions", Context.MODE_PRIVATE)

    fun dismissThrough(time: Long) {
        if (time > prefs.getLong("last_offered", 0)) prefs.edit().putLong("last_offered", time).apply()
    }

    fun canOffer(shot: RecentScreenshot) = shot.isFresh() && shot.capturedAt > prefs.getLong("last_offered", 0)

    suspend fun read(folder: String): List<RecentScreenshot> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cutoff = maxOf(now - SCREENSHOT_LIFETIME_MS, prefs.getLong("private_cutoff", 0))
        try {
            val tree = Uri.parse(folder)
            val directory = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory)
            val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
            resolver.query(children, projection, null as Bundle?, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        ensureActive()
                        val time = cursor.getLong(2)
                        if (time <= cutoff || time > now) continue
                        val mime = cursor.getString(1)?.takeIf { it in supportedTypes } ?: continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                        add(RecentScreenshot(uri, mime, time))
                    }
                }.sortedByDescending { it.capturedAt }.take(8)
            }.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { emptyList() }
    }

    fun skipPrivateShots() {
        prefs.edit().putLong("private_cutoff", System.currentTimeMillis()).apply()
    }

    private companion object {
        val supportedTypes = setOf("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif")
    }
}

@Composable
internal fun rememberRecentScreenshots(source: RecentScreenshots, folder: String?, active: Boolean,
    privateMode: Boolean, session: Int): List<RecentScreenshot> {
    DisposableEffect(source, privateMode, active) {
        if (privateMode && active) source.skipPrivateShots()
        onDispose { if (privateMode && active) source.skipPrivateShots() }
    }
    var screenshots by remember(folder, active, privateMode, session) { mutableStateOf(emptyList<RecentScreenshot>()) }
    LaunchedEffect(source, folder, active, privateMode, session) {
        if (folder == null || !active || privateMode) return@LaunchedEffect
        var found = source.read(folder)
        if (found.isEmpty()) {
            delay(500)
            found = source.read(folder)
        }
        while (found.isNotEmpty()) {
            screenshots = found.filter { it.isFresh() }
            val nextExpiry = screenshots.minOfOrNull { it.expiresAt } ?: break
            delay((nextExpiry - System.currentTimeMillis()).coerceAtLeast(1L))
            found = screenshots.filter { it.isFresh() }
        }
        screenshots = emptyList()
    }
    return if (folder == null || !active || privateMode) emptyList() else screenshots
}

@Composable
internal fun ScreenshotThumbnail(shot: RecentScreenshot, modifier: Modifier = Modifier, toolbar: Boolean = false) {
    val resolver = LocalContext.current.contentResolver
    val edge = if (toolbar) 100 else 220
    val bitmap by produceState<Bitmap?>(null, shot.uri, edge) {
        value = withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, shot.uri)) { decoder, info, _ ->
                    val size = info.size
                    val scale = (edge.toFloat() / maxOf(size.width, size.height)).coerceAtMost(1f)
                    decoder.setTargetSize((size.width * scale).roundToInt().coerceAtLeast(1),
                        (size.height * scale).roundToInt().coerceAtLeast(1))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { null }
            catch (_: RuntimeException) { null }
        }
    }
    val image = bitmap
    if (image == null) Text("Screenshot", modifier)
    else Image(image.asImageBitmap(), contentDescription = null,
        modifier.size(if (toolbar) 29.dp else 58.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
}
