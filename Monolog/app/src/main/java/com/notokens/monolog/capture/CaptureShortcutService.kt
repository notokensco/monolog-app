package com.notokens.monolog.capture

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class CaptureShortcutService : AccessibilityService() {
    private val button = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            if (ScreenCaptureService.isReady) ScreenCaptureService.shortcutClicked()
            else android.widget.Toast.makeText(this@CaptureShortcutService,
                "Enable Monolog Keyboard Context in Accessibility first.", android.widget.Toast.LENGTH_SHORT).show()
        }

        override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
            if (!available) ScreenCaptureService.closeShortcutMenu()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        enabled = true
        accessibilityButtonController.registerAccessibilityButtonCallback(button)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = ScreenCaptureService.closeShortcutMenu()

    override fun onDestroy() {
        enabled = false
        accessibilityButtonController.unregisterAccessibilityButtonCallback(button)
        ScreenCaptureService.closeShortcutMenu()
        super.onDestroy()
    }

    companion object {
        internal var enabled = false
            private set
    }
}
