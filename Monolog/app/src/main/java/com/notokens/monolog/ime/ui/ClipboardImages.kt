package com.notokens.monolog.ime.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.core.content.FileProvider
import com.notokens.monolog.ui.theme.PaperPillBackground
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ClipboardImage(val file: File, val mime: String, val timestamp: Long) {
    fun uri(context: Context): Uri = FileProvider.getUriForFile(context, "${context.packageName}.imeclipboard", file)
}

private const val IMAGE_LIFETIME = 3_600_000L
private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
private val imageTypes = mapOf("png" to "image/png", "jpeg" to "image/jpeg", "webp" to "image/webp",
    "gif" to "image/gif", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "bmp" to "image/bmp")
@Volatile private var privateUntil = 0L
private var deletedTimestamp = 0L

internal class ClipboardImages(private val context: Context) {
    private val folder = File(context.filesDir, "ime-clipboard")
    private val prefs = context.getSharedPreferences("ime_clipboard_images", Context.MODE_PRIVATE)
    private var pins = prefs.getStringSet("pinned", emptySet()).orEmpty().toSet()
    var items by mutableStateOf(emptyList<ClipboardImage>())
        private set
    fun pinned(image: ClipboardImage) = image.file.name in pins

    suspend fun refresh(clearRecent: Boolean = false) {
        val saved = pins
        items = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var recent = 0
            folder.listFiles().orEmpty().sortedByDescending { it.nameWithoutExtension.toLongOrNull() ?: 0L }
                .mapNotNull { file ->
                    val timestamp = file.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
                    val mime = imageTypes[file.extension] ?: return@mapNotNull null
                    val pin = file.name in saved
                    if (!pin && (clearRecent || now - timestamp >= IMAGE_LIFETIME || ++recent > 8)) {
                        file.delete()
                        null
                    } else ClipboardImage(file, mime, timestamp)
                }
        }
    }

    suspend fun receive(uri: Uri, announcedMime: String?, timestamp: Long): Boolean {
        if (timestamp <= privateUntil || timestamp == deletedTimestamp || System.currentTimeMillis() - timestamp >= IMAGE_LIFETIME) return false
        val image = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val types = listOfNotNull(runCatching { resolver.getType(uri) }.getOrNull(), announcedMime) +
                    runCatching { resolver.getStreamTypes(uri, "image/*").orEmpty().toList() }.getOrDefault(emptyList())
                val match = types.firstNotNullOfOrNull { raw ->
                    val mime = raw.substringBefore(';').lowercase().let { if (it == "image/jpg") "image/jpeg" else it }
                    imageTypes.entries.firstOrNull { it.value == mime }
                } ?: return@runCatching false
                val (extension, mime) = match
                folder.mkdirs()
                val destination = File(folder, "$timestamp.$extension")
                if (!destination.isFile) {
                    val partial = File.createTempFile("clipboard-", ".part", folder)
                    try {
                        val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
                            ?: resolver.openTypedAssetFileDescriptor(uri, mime, null)?.createInputStream()
                            ?: return@runCatching false
                        stream.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var bytes = 0L
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    bytes += count
                                    check(bytes <= MAX_IMAGE_BYTES)
                                    output.write(buffer, 0, count)
                                }
                                check(bytes > 0)
                            }
                            ensureActive()
                            check(timestamp > privateUntil)
                            android.system.Os.rename(partial.path, destination.path)
                        }
                    } finally { partial.delete() }
                }
                true
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                false
            }
        }
        if (image) refresh()
        return image
    }

    suspend fun setPinned(clip: ClipboardImage, pinned: Boolean) {
        pins = if (pinned) (listOf(clip.file.name) + pins).distinct().take(8).toSet() else pins - clip.file.name
        prefs.edit().putStringSet("pinned", pins).apply()
        refresh()
    }

    suspend fun remove(clip: ClipboardImage) {
        deletedTimestamp = clip.timestamp
        setPinned(clip, false)
        withContext(Dispatchers.IO) { clip.file.delete() }
        refresh()
    }
}

internal suspend fun clearClipboardImageStorage(context: Context) = withContext(Dispatchers.IO) {
    context.getSharedPreferences("ime_clipboard_images", Context.MODE_PRIVATE).edit().clear().commit()
    File(context.filesDir, "ime-clipboard").listFiles()?.forEach { if (it.isFile) it.delete() }
}

@Composable
internal fun rememberClipboardImages(enabled: Boolean, privateMode: Boolean, visible: Boolean): ClipboardImages {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ClipboardImages(context) }
    val scope = rememberCoroutineScope()
    DisposableEffect(enabled, privateMode, visible, store) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        var importJob: kotlinx.coroutines.Job? = null
        fun update() {
            val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return
            val description = clip.description
            if (description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true || clip.itemCount == 0) return
            val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.takeIf { uri -> uri.scheme == "content" && uri.authority != "${context.packageName}.imemedia" } }
            if (uris.isEmpty()) return
            val mime = description.filterMimeTypes("image/*")?.firstOrNull()
            importJob?.cancel()
            importJob = scope.launch {
                for (uri in uris) if (store.receive(uri, mime, description.timestamp)) break
            }
        }
        if (privateMode) privateUntil = System.currentTimeMillis()
        val permitted = enabled && visible && !privateMode
        val listener = ClipboardManager.OnPrimaryClipChangedListener { update() }
        if (permitted) {
            manager?.addPrimaryClipChangedListener(listener)
            update()
        }
        onDispose {
            importJob?.cancel()
            if (permitted) manager?.removePrimaryClipChangedListener(listener)
            if (privateMode) privateUntil = System.currentTimeMillis()
        }
    }
    LaunchedEffect(enabled, privateMode, visible, store) {
        store.refresh(clearRecent = !enabled || privateMode)
        while (enabled && !privateMode && visible) {
            delay(15_000)
            store.refresh()
        }
    }
    return store
}

@Composable
internal fun ClipboardImagePreview(clip: ClipboardImage, expanded: Boolean, modifier: Modifier = Modifier) {
    val maximum = if (expanded) 640 else 288
    val bitmap by produceState<Bitmap?>(null, clip.file, maximum) {
        value = withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(clip.file)) { decoder, info, _ ->
                    val size = info.size
                    val scale = (maximum.toFloat() / maxOf(size.width, size.height)).coerceAtMost(1f)
                    decoder.setTargetSize((size.width * scale).roundToInt().coerceAtLeast(1),
                        (size.height * scale).roundToInt().coerceAtLeast(1))
                }
            } catch (_: java.io.IOException) {
                null
            }
        }
    }
    val image = bitmap
    val shape = RoundedCornerShape(9.dp)
    if (image == null) {
        Text("Image", Modifier.size(55.dp).clip(shape).background(PaperPillBackground).then(modifier))
    } else {
        val maxWidth = if (expanded) 220f else 94f
        val maxHeight = if (expanded) 142f else 58f
        val scale = minOf(maxWidth / image.width, maxHeight / image.height)
        val width = (image.width * scale).coerceAtLeast(1f).dp
        val height = (image.height * scale).coerceAtLeast(1f).dp
        Image(image.asImageBitmap(), "Copied image",
            Modifier.size(width, height).clip(shape).then(modifier), contentScale = ContentScale.Fit)
    }
}
