package com.notokens.monolog.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.content.ComponentName
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenCaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capture: Job? = null
    private val settings by lazy { SettingsStore(applicationContext) }
    private val floating by lazy { FloatingContextDock(this) { saveScreenshot(Display.DEFAULT_DISPLAY, hideKeyboard = true) } }
    private var dockEnabled = false
    private var dockJob: Job? = null
    private val shortcutObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshSystemShortcut()
    }
    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) floating.hide()
            else refreshSystemShortcut()
        }
    }
    private val contextButton = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            saveScreenshot(Display.DEFAULT_DISPLAY, hideKeyboard = true)
        }
        override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
            refreshSystemShortcut()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        accessibilityButtonController.registerAccessibilityButtonCallback(contextButton)
        if (dockJob == null) {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(BUTTON_TARGETS), false, shortcutObserver)
            refreshSystemShortcut()
            registerReceiver(screen, android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }, RECEIVER_NOT_EXPORTED)
            dockJob = scope.launch {
                combine(settings.floatingContextDock, settings.incognito, settings.shortcutHoldMs,
                    settings.shortcutHaptics, settings.quickShortcutOrder) { active, incognito, hold, haptics, order ->
                    floating.configure(hold, haptics, order)
                    active && !incognito
                }.collect {
                    dockEnabled = it
                    syncFloatingButton()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { capture?.cancel() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floating.reposition()
        refreshSystemShortcut()
    }

    private fun refreshSystemShortcut() {
        val component = ComponentName(this, ScreenCaptureService::class.java)
        // Availability applies to the system's button in general. Check Monolog's
        // assignment so shortcuts for other accessibility services are unaffected.
        nativeContextShortcut.value = try {
            Settings.Secure.getString(contentResolver, BUTTON_TARGETS).orEmpty().split(':')
                .any { ComponentName.unflattenFromString(it) == component }
        } catch (_: SecurityException) {
            accessibilityButtonController.isAccessibilityButtonAvailable
        }
        syncFloatingButton()
    }

    private fun syncFloatingButton(starting: Boolean = false) {
        val power = getSystemService(android.os.PowerManager::class.java)
        if (dockEnabled && !nativeContextShortcut.value && (starting || capture?.isActive != true) &&
            power?.isInteractive == true) floating.show()
        else floating.hide()
    }

    override fun onDestroy() {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(contextButton)
        if (connected === this) connected = null
        if (dockJob != null) {
            unregisterReceiver(screen)
            contentResolver.unregisterContentObserver(shortcutObserver)
        }
        dockEnabled = false
        nativeContextShortcut.value = false
        scope.cancel()
        floating.hide()
        super.onDestroy()
    }

    private fun saveScreenshot(displayId: Int, hideKeyboard: Boolean = false) {
        if (capture?.isActive == true) return
        capture = scope.launch {
            val keyboard = softKeyboardController
            val previousMode = keyboard.showMode
            var temporarilyHidden = false
            try {
                captureToJournal(this@ScreenCaptureService, settings) {
                    if (dockEnabled) floating.hide()
                    if (hideKeyboard) {
                        temporarilyHidden = previousMode != SHOW_MODE_HIDDEN && keyboard.setShowMode(SHOW_MODE_HIDDEN)
                        delay(300)
                    } else if (dockEnabled) delay(100)
                    screenshot(displayId)
                }
            } finally {
                if (temporarilyHidden) keyboard.setShowMode(previousMode)
                syncFloatingButton(starting = true)
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
        private const val BUTTON_TARGETS = "accessibility_button_targets"
        private val nativeContextShortcut = MutableStateFlow(false)
        val systemShortcutAssigned = nativeContextShortcut.asStateFlow()
        private var connected: ScreenCaptureService? = null
        val isReady: Boolean get() = connected != null

        fun capture(displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
            val service = connected ?: return false
            service.saveScreenshot(displayId)
            return true
        }
    }
}
