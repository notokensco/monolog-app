package com.notokens.monolog.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

object PhotoCodec {
    const val MAX_ATTACHMENTS = 8
    private const val MAX_PHOTO_BYTES = 480 * 1024

    fun decode(resolver: ContentResolver, uri: Uri, maxEdge: Int = 1600): Bitmap {
        return decode(ImageDecoder.createSource(resolver, uri), maxEdge)
    }

    fun decode(bytes: ByteArray, maxEdge: Int): Bitmap =
        decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxEdge)

    private fun decode(source: ImageDecoder.Source, maxEdge: Int): Bitmap {
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val ratio = maxEdge.toFloat() / maxOf(width, height)
            if (ratio < 1f) {
                decoder.setTargetSize((width * ratio).roundToInt().coerceAtLeast(1), (height * ratio).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun jpeg(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        for (quality in listOf(88, 75, 60, 45, 30, 15)) {
            output.reset()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() in 1..MAX_PHOTO_BYTES) {
                return output.toByteArray()
            }
        }
        throw IOException("Photo is too large to save")
    }
}
