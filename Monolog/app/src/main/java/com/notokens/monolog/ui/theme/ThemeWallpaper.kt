package com.notokens.monolog.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.system.Os
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ThemeWallpaper {
    fun file(context: Context) = File(context.noBackupFilesDir, "theme-wallpaper.jpg")

    suspend fun save(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val factor = (1440f / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize((info.size.width * factor).toInt().coerceAtLeast(1),
                (info.size.height * factor).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val temporary = File.createTempFile("wallpaper-", ".part", context.noBackupFilesDir)
        try {
            temporary.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 90, it)); it.fd.sync() }
            Os.rename(temporary.path, file(context).path)
        } finally {
            image.recycle()
            temporary.delete()
        }
    }
}
