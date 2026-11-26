package com.notokens.monolog.ime.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.notokens.monolog.R
import com.notokens.monolog.data.KeyTapSound
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalKeySound = staticCompositionLocalOf<(() -> Unit)?> { null }

private fun customSound(context: Context) = File(context.filesDir, "custom-key-tap")

internal suspend fun importKeySound(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    val target = customSound(context)
    val temporary = File.createTempFile("key-tap-", ".tmp", context.filesDir)
    try {
        val stream = context.contentResolver.openInputStream(uri) ?: error("Couldn’t open the sound file.")
        stream.use { source ->
            temporary.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytes = 0
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    bytes += read
                    require(bytes <= 1_000_000) { "Choose a key sound under 1 MB." }
                    output.write(buffer, 0, read)
                }
                require(bytes > 0) { "Choose a sound file with audio." }
            }
        }
        android.media.MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(temporary.path)
            val length = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            require(length != null && length in 1..1000) { "Choose an audio clip up to one second long." }
        }
        android.system.Os.rename(temporary.path, target.path)
    } finally {
        temporary.delete()
    }
}

@Composable
internal fun rememberKeySound(option: KeyTapSound, revision: Long): (() -> Unit)? {
    val context = LocalContext.current.applicationContext
    var play by remember(option, revision) { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(context, option, revision) {
        if (option == KeyTapSound.OFF) return@DisposableEffect onDispose {}
        val audio = context.getSystemService(AudioManager::class.java)
        val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()).build()
        var loaded = 0
        pool.setOnLoadCompleteListener { _, sample, status ->
            if (status == 0) loaded = sample
        }
        runCatching {
            when (option) {
                KeyTapSound.TICK -> pool.load(context, R.raw.key_tick, 1)
                KeyTapSound.TYPEWRITER -> pool.load(context, R.raw.key_typewriter, 1)
                KeyTapSound.CUSTOM -> pool.load(customSound(context).path, 1)
                KeyTapSound.OFF -> Unit
            }
        }
        play = {
            val sample = loaded
            if (sample == 0 || pool.play(sample, 1f, 1f, 1, 0, 1f) == 0) {
                audio.playSoundEffect(AudioManager.FX_KEY_CLICK)
            }
        }
        onDispose {
            pool.setOnLoadCompleteListener(null)
            pool.release()
        }
    }
    return play
}
