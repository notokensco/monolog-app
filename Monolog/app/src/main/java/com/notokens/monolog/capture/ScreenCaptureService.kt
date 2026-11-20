package com.notokens.monolog.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.notokens.monolog.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenCaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capture: Job? = null
    private val settings by lazy { SettingsStore(applicationContext) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { capture?.cancel() }

    override fun onDestroy() {
        if (connected === this) connected = null
        scope.cancel()
        super.onDestroy()
    }

    private fun saveScreenshot(displayId: Int) {
        if (capture?.isActive == true) return
        capture = scope.launch {
            captureToJournal(this@ScreenCaptureService, settings) { screenshot(displayId) }
        }
    }

    private suspend fun screenshot(displayId: Int): Bitmap = suspendCancellableCoroutine { continuation ->
        takeScreenshot(displayId, Dispatchers.Default.asExecutor(), object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                result.hardwareBuffer.use { buffer ->
                    if (!continuation.isActive) return
                    try {
                        val hardwareBitmap = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace))
                        val bitmap = try {
                            checkNotNull(hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false))
                        } finally {
                            hardwareBitmap.recycle()
                        }
                        continuation.resume(bitmap)
                    } catch (error: RuntimeException) {
                        continuation.resumeWithException(error)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                val message = when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This screen blocks screenshots"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Wait a moment before capturing again"
                    else -> "Android couldn’t capture this screen"
                }
                continuation.resumeWithException(CaptureException(message))
            }
        })
    }

    companion object {
        private var connected: ScreenCaptureService? = null
        val isReady: Boolean get() = connected != null

        fun capture(displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
            val service = connected ?: return false
            service.saveScreenshot(displayId)
            return true
        }
    }
}
