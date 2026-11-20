package com.notokens.monolog.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.notokens.monolog.R
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal sealed interface CaptureOutcome {
    data class Photo(val bitmap: Bitmap) : CaptureOutcome
    data class Failure(val message: String) : CaptureOutcome
}

internal data class CaptureResult(val sessionId: String, val outcome: CaptureOutcome)
internal val captureResults = MutableStateFlow<CaptureResult?>(null)

class ProjectionCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var sessionId: String? = null
    private var imageReader: ImageReader? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            complete(CaptureOutcome.Failure("Android ended the capture before an image was ready."))
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val activeDisplay = display ?: return
            val (targetWidth, targetHeight) = captureSize(width, height)
            val previous = imageReader ?: return
            if (previous.width == targetWidth && previous.height == targetHeight) return
            try {
                val next = createReader(targetWidth, targetHeight)
                imageReader = next
                try {
                    activeDisplay.resize(targetWidth, targetHeight, resources.displayMetrics.densityDpi)
                    activeDisplay.surface = next.surface
                } finally {
                    previous.close()
                }
            } catch (_: RuntimeException) {
                complete(CaptureOutcome.Failure("Android couldn’t resize the captured screen."))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (sessionId != null) return START_NOT_STICKY
        val requestedId = intent?.getStringExtra(SESSION_ID)
        val consent = intent?.getParcelableExtra(CONSENT, Intent::class.java)
        if (requestedId == null || consent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        sessionId = requestedId

        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val manager = getSystemService(MediaProjectionManager::class.java)
            val activeProjection = checkNotNull(manager.getMediaProjection(Activity.RESULT_OK, consent))
            projection = activeProjection
            activeProjection.registerCallback(callback, handler)
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val (width, height) = captureSize(bounds.width(), bounds.height())
            val reader = createReader(width, height).also { imageReader = it }
            display = checkNotNull(activeProjection.createVirtualDisplay(
                "Monolog screenshot", width, height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
            ))
            // Give the system consent window and keyboard time to leave the captured surface.
            handler.postDelayed({ readFrame(remainingAttempts = 20) }, 850)
        } catch (_: RuntimeException) {
            complete(CaptureOutcome.Failure("Monolog couldn’t start a screen capture. Try again from the keyboard."))
        }
        return START_NOT_STICKY
    }

    private fun readFrame(remainingAttempts: Int) {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                if (remainingAttempts == 0) complete(CaptureOutcome.Failure("Android didn’t provide a screenshot."))
                else handler.postDelayed({ readFrame(remainingAttempts - 1) }, 120)
                return
            }
            val bitmap = image.use {
                val plane = it.planes.first()
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, it.height, Bitmap.Config.ARGB_8888)
                val buffer = plane.buffer
                val required = padded.rowBytes * padded.height
                val pixels = if (buffer.remaining() >= required) buffer else
                    ByteBuffer.allocate(required).apply { put(buffer); rewind() }
                padded.copyPixelsFromBuffer(pixels)
                Bitmap.createBitmap(padded, 0, 0, it.width, it.height).also { crop ->
                    if (crop !== padded) padded.recycle()
                }
            }
            val hasContent = (0..15).any { y ->
                (0..15).any { x ->
                    val pixel = bitmap.getPixel(x * (bitmap.width - 1) / 15, y * (bitmap.height - 1) / 15)
                    Color.alpha(pixel) > 0 && maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 8
                }
            }
            if (hasContent) complete(CaptureOutcome.Photo(bitmap))
            else {
                bitmap.recycle()
                complete(CaptureOutcome.Failure("Android returned a blank image. Some screens block screenshots."))
            }
        } catch (_: RuntimeException) {
            complete(CaptureOutcome.Failure("Monolog couldn’t read the screenshot."))
        }
    }

    private fun complete(outcome: CaptureOutcome) {
        val current = sessionId ?: return
        sessionId = null
        releaseCapture()
        captureResults.value = CaptureResult(current, outcome)
        stopSelf()
    }

    private fun releaseCapture() {
        handler.removeCallbacksAndMessages(null)
        display?.release()
        display = null
        imageReader?.close()
        imageReader = null
        projection?.let {
            it.unregisterCallback(callback)
            it.stop()
        }
        projection = null
    }

    override fun onDestroy() {
        releaseCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.monolog_capture_notification)
            .setContentTitle("Capturing one screenshot")
            .setOngoing(true)
            .build()
    }

    private fun captureSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, 1800f / maxOf(width, height).coerceAtLeast(1))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun createReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

    companion object {
        private const val SESSION_ID = "session"
        private const val CONSENT = "consent"
        private const val CHANNEL_ID = "context_capture"
        private const val NOTIFICATION_ID = 2048

        internal fun start(context: Context, id: String, consent: Intent) {
            ContextCompat.startForegroundService(context, Intent(context, ProjectionCaptureService::class.java).apply {
                putExtra(SESSION_ID, id)
                putExtra(CONSENT, consent)
            })
        }
    }
}
