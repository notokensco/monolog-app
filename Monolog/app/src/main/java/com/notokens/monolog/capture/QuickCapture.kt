package com.notokens.monolog.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.PixelCopy
import android.view.View
import android.widget.Toast
import com.notokens.monolog.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun captureToJournal(context: Context, settings: SettingsStore, capture: suspend () -> Bitmap) {
    val message = try {
        if (settings.incognito.first()) throw CaptureException("Quick capture is paused in incognito mode")
        val bitmap = withTimeoutOrNull(3_000) { capture() }
            ?: throw CaptureException("Android didn’t return a screenshot. Try again.")
        if (bitmap.isBlankCapture()) throw CaptureException("This screen is blank or blocks screenshots")

        PendingCaptureHolder.bitmap = bitmap
        val intent = Intent(context, CaptureAnnotationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            if (PendingCaptureHolder.bitmap === bitmap) PendingCaptureHolder.bitmap = null
            throw error
        }
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: CaptureException) {
        error.message.orEmpty()
    } catch (_: Exception) {
        "Couldn’t capture the screen. Try again."
    }
    if (message != null) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

internal suspend fun copyCompanionWindow(view: View): Bitmap = suspendCancellableCoroutine { continuation ->
    val request = PixelCopy.Request.Builder.ofWindow(view).build()
    PixelCopy.request(request, view.context.mainExecutor) { result ->
        if (result.status == PixelCopy.SUCCESS) continuation.resume(result.bitmap)
        else continuation.resumeWithException(CaptureException("Android couldn’t capture this screen"))
    }
}

internal class CaptureException(message: String) : Exception(message)

private fun Bitmap.isBlankCapture(): Boolean {
    for (row in 1..7) for (column in 1..7) {
        val pixel = getPixel(width * column / 8, height * row / 8)
        if (pixel and 0x00FFFFFF != 0) return false
    }
    return true
}
