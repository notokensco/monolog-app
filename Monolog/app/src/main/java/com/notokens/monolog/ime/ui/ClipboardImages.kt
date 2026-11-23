package com.notokens.monolog.ime.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
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

    suspend fun receive(uri: Uri, announcedMime: String?, timestamp: Long) {
        if (timestamp <= privateUntil || timestamp == deletedTimestamp || System.currentTimeMillis() - timestamp >= IMAGE_LIFETIME) return
        val image = withContext(Dispatchers.IO) {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: announcedMime ?: return@runCatching
                val extension = imageTypes.entries.firstOrNull { it.value == mime }?.key ?: return@runCatching
                folder.mkdirs()
                val destination = File(folder, "$timestamp.$extension")
                if (!destination.isFile) {
                    val partial = File.createTempFile("clipboard-", ".part", folder)
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
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
            }
        }
        if (image.isSuccess) refresh()
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
            val uri = clip.getItemAt(0).uri?.takeIf { it.scheme == "content" } ?: return
            val mime = description.filterMimeTypes("image/*")?.firstOrNull()
            importJob?.cancel()
            importJob = scope.launch { store.receive(uri, mime, description.timestamp) }
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
internal fun ClipboardImagePreview(clip: ClipboardImage, expanded: Boolean) {
    val maximum = if (expanded) 640 else 128
    val bitmap by produceState<Bitmap?>(null, clip.file, maximum) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(clip.file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maximum * 2) sample *= 2
            BitmapFactory.decodeFile(clip.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    val image = bitmap
    if (image == null) Text("Image", Modifier.heightIn(min = 44.dp))
    else Image(image.asImageBitmap(), "Copied image", Modifier.size(if (expanded) 146.dp else 62.dp))
}
