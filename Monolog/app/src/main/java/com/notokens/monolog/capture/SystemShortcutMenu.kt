package com.notokens.monolog.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import com.notokens.monolog.MainActivity
import com.notokens.monolog.R
import com.notokens.monolog.data.QuickShortcut
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Reads only the real Android shortcut's SystemUI window or explicitly identified button nodes. */
internal class SystemShortcutAnchor(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val systemUi = "com.android.systemui"
    private val buttonIds = listOf(
        "accessibility_button", "accessibility_button_frame",
        "accessibility_floating_menu_view", "menu_view", "menu_list_view", "floating_menu_view"
    ).map { "$systemUi:id/$it" }

    private fun compact(bounds: Rect): Boolean {
        val limit = (140 * service.resources.displayMetrics.density).roundToInt()
        return !bounds.isEmpty && bounds.width() <= limit && bounds.height() <= limit &&
            windowManager.maximumWindowMetrics.bounds.contains(bounds)
    }

    fun clicked(node: AccessibilityNodeInfo?): Rect? {
        if (node?.packageName?.toString() != systemUi || !node.isVisibleToUser) return null
        if (node.viewIdResourceName !in buttonIds &&
            node.contentDescription?.toString() != service.getString(R.string.quick_capture_name)) return null
        return Rect().also(node::getBoundsInScreen).takeIf(::compact)
    }

    fun locate(): Rect? {
        val fallback = mutableListOf<Rect>()
        for (window in service.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val title = window.title?.toString()
            val floatingShortcut = title == "FloatingMenu" || title == "AccessibilityFloatingMenu"
            val root = window.root ?: continue
            if (root.packageName?.toString() != systemUi) continue
            if (floatingShortcut) {
                val label = service.getString(R.string.quick_capture_name)
                root.findAccessibilityNodeInfosByText(label).mapNotNull(::clicked)
                    .distinct().singleOrNull()?.let { return it }
                val bounds = Rect().also(window::getBoundsInScreen)
                if (compact(bounds)) return bounds
            }
            for (id in buttonIds) {
                if (!floatingShortcut && id.endsWith("menu_view")) continue
                root.findAccessibilityNodeInfosByViewId(id).mapNotNullTo(fallback, ::clicked)
            }
        }
        return fallback.distinct().minByOrNull { it.width() * it.height() }
    }
}

/** Displays actions around a known system button. The button itself always belongs to Android. */
internal class SystemShortcutMenu(private val context: Context) {
    private data class Action(val label: String, val intentAction: String)
    private data class Chip(val action: Action, val bounds: Rect)

    private val windows = context.getSystemService(WindowManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { hide() }
    private var view: View? = null
    val isShowing get() = view != null

    fun hide() {
        handler.removeCallbacks(dismiss)
        val old = view ?: return
        if (runCatching { windows.removeViewImmediate(old) }.isSuccess || !old.isAttachedToWindow) view = null
    }

    fun show(anchor: Rect, order: List<QuickShortcut>, haptics: Boolean) {
        hide()
        if (view != null || keyguard?.isKeyguardLocked == true) return
        val metrics = windows.maximumWindowMetrics
        val safe = Rect(metrics.bounds)
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        safe.left += insets.left
        safe.top += insets.top
        safe.right -= insets.right
        safe.bottom -= insets.bottom
        val actions = order.map {
            when (it) {
                QuickShortcut.REMINDER -> Action("Remind", MainActivity.ACTION_NEW_REMINDER)
                QuickShortcut.ALARM -> Action("Alarm", "alarm")
                QuickShortcut.JOURNAL -> Action("Journal", MainActivity.ACTION_OPEN_JOURNAL)
            }
        } + Action("Note", MainActivity.ACTION_NEW_NOTE)
        val chips = place(anchor, safe, actions, metrics.bounds) ?: return
        val region = Rect(chips.first().bounds)
        chips.drop(1).forEach { region.union(it.bounds) }
        val menu = FrameLayout(context)
        chips.forEach { chip ->
            val item = TextView(context).apply {
                text = chip.action.label
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(Color.WHITE)
                isHapticFeedbackEnabled = haptics
                background = GradientDrawable().apply {
                    cornerRadius = dp(27).toFloat()
                    setColor(0xEC363C63.toInt())
                    setStroke(dp(1), 0x4AFFFFFF)
                }
                setOnClickListener { launch(chip.action.intentAction) }
            }
            menu.addView(item, FrameLayout.LayoutParams(chip.bounds.width(), chip.bounds.height()).apply {
                leftMargin = chip.bounds.left - region.left
                topMargin = chip.bounds.top - region.top
            })
        }
        menu.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) hide()
            false
        }
        val params = WindowManager.LayoutParams(region.width(), region.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = region.left - metrics.bounds.left
            y = region.top - metrics.bounds.top
            title = "Monolog shortcut actions"
        }
        runCatching { windows.addView(menu, params) }.onSuccess {
            view = menu
            if (haptics) menu.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            handler.postDelayed(dismiss, 6000)
        }
    }

    private fun place(anchor: Rect, safe: Rect, actions: List<Action>, screen: Rect): List<Chip>? {
        val side = dp(54)
        if (safe.width() < side || safe.height() < side) return null
        val half = side / 2
        val centerX = anchor.exactCenterX()
        val centerY = anchor.exactCenterY()
        val inward = listOf(centerX - screen.left to 0, screen.right - centerX to 180,
            centerY - screen.top to 90, screen.bottom - centerY to -90).sortedBy { it.first }
        for ((_, heading) in inward) {
            for (bias in listOf(0, -19, 19, -35, 35)) {
                val chips = actions.mapIndexed { index, action ->
                    val angle = Math.toRadians(heading + bias + (index - (actions.size - 1) / 2.0) * 32)
                    val left = (centerX + cos(angle) * dp(108)).roundToInt() - half
                    val top = (centerY + sin(angle) * dp(108)).roundToInt() - half
                    val x = left.coerceIn(safe.left, safe.right - side)
                    val y = top.coerceIn(safe.top, safe.bottom - side)
                    Chip(action, Rect(x, y, x + side, y + side))
                }
                val region = Rect(chips.first().bounds)
                chips.drop(1).forEach { region.union(it.bounds) }
                if (!Rect.intersects(region, anchor) && chips.indices.none { index ->
                    (0 until index).any { previous -> Rect.intersects(chips[index].bounds, chips[previous].bounds) }
                }) return chips
            }
        }
        return null
    }

    private fun launch(action: String) {
        hide()
        val intent = if (action == "alarm") Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            else Intent(context, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "No app is available for this action.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
