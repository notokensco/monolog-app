package com.notokens.monolog.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.notokens.monolog.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenCaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capture: Job? = null
    private val settings by lazy { SettingsStore(applicationContext) }
    private val shortcutMenu by lazy { SystemShortcutMenu(this) }
    private val shortcutAnchor by lazy { SystemShortcutAnchor(this) }
    private var shortcutTap: Job? = null
    private var lastSystemButtonClick: Pair<Rect, Long>? = null
    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            shortcutTap?.cancel()
            shortcutTap = null
            lastSystemButtonClick = null
            shortcutMenu.hide()
        }
    }
    private val contextButton = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            if (shortcutTap?.isActive == true) {
                shortcutTap?.cancel()
                shortcutTap = null
                showSystemShortcutMenu()
            } else {
                shortcutTap = scope.launch {
                    delay(android.view.ViewConfiguration.getDoubleTapTimeout().toLong())
                    shortcutTap = null
                    saveScreenshot(Display.DEFAULT_DISPLAY, hideKeyboard = true)
                }
            }
        }

        override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
            if (!available) {
                shortcutTap?.cancel()
                shortcutTap = null
                shortcutMenu.hide()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        accessibilityButtonController.registerAccessibilityButtonCallback(contextButton)
        if (!screenRegistered) {
            registerReceiver(screen, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),
                RECEIVER_NOT_EXPORTED)
            screenRegistered = true
        }
    }

    private var screenRegistered = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.android.systemui") return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) return
        val anchor = shortcutAnchor.clicked(event.source) ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            shortcutTap?.cancel()
            shortcutTap = null
            showSystemShortcutMenu(anchor)
        } else {
            lastSystemButtonClick = anchor to event.eventTime
        }
    }

    private fun showSystemShortcutMenu(heldAnchor: Rect? = null) {
        if (shortcutMenu.isShowing) {
            if (heldAnchor == null) shortcutMenu.hide()
            return
        }
        scope.launch {
            if (settings.incognito.first()) return@launch
            val recent = lastSystemButtonClick?.takeIf {
                SystemClock.uptimeMillis() - it.second in 0..450L
            }?.first
            val anchor = heldAnchor ?: shortcutAnchor.locate() ?: recent ?: return@launch
            shortcutMenu.show(anchor, settings.quickShortcutOrder.first(), settings.shortcutHaptics.first())
        }
    }

    override fun onInterrupt() {
        capture?.cancel()
        shortcutTap?.cancel()
        shortcutTap = null
        shortcutMenu.hide()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lastSystemButtonClick = null
        shortcutMenu.hide()
    }

    override fun onDestroy() {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(contextButton)
        if (connected === this) connected = null
        if (screenRegistered) unregisterReceiver(screen)
        scope.cancel()
        shortcutMenu.hide()
        super.onDestroy()
    }

    private fun saveScreenshot(displayId: Int, hideKeyboard: Boolean = false) {
        if (capture?.isActive == true) return
        shortcutMenu.hide()
        capture = scope.launch {
            val keyboard = softKeyboardController
            val previousMode = keyboard.showMode
            var temporarilyHidden = false
            try {
                captureToJournal(this@ScreenCaptureService, settings) {
                    if (hideKeyboard) {
                        temporarilyHidden = previousMode != SHOW_MODE_HIDDEN && keyboard.setShowMode(SHOW_MODE_HIDDEN)
                        delay(300)
                    }
                    screenshot(displayId)
                }
            } finally {
                if (temporarilyHidden) keyboard.setShowMode(previousMode)
            }
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
                if (!continuation.isActive) return
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

        fun capture(displayId: Int = Display.DEFAULT_DISPLAY, hideKeyboard: Boolean = false): Boolean {
            val service = connected ?: return false
            service.saveScreenshot(displayId, hideKeyboard)
            return true
        }
    }
}
