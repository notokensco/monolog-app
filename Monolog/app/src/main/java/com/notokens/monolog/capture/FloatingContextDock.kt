package com.notokens.monolog.capture

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.notokens.monolog.MainActivity
import com.notokens.monolog.R
import com.notokens.monolog.data.QuickShortcut
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal class FloatingContextDock(private val context: Context, private val capture: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val lock = context.getSystemService(KeyguardManager::class.java)
    private val position = context.getSharedPreferences("floating_context_position", Context.MODE_PRIVATE)
    private var dockRight = position.getBoolean("right", true)
    private var verticalPosition = position.getFloat("vertical", .5f).coerceIn(0f, 1f)
    private var view: FrameLayout? = null
    private var holdMs = 500L
    private var haptics = true
    private var actions = QuickShortcut.entries.toList()

    private data class DockAction(val label: String, val intentAction: String)
    private data class Chip(val action: DockAction, val center: Point)

    fun configure(hold: Int, feedback: Boolean, order: List<QuickShortcut>) {
        holdMs = hold.toLong()
        haptics = feedback
        actions = order
    }

    fun show() {
        if (view == null && lock?.isKeyguardLocked != true) render(false)
    }

    fun showShortcuts() = render(true)

    fun reposition() {
        if (view != null) render(false)
    }

    fun hide() {
        view?.let { runCatching { windows.removeViewImmediate(it) } }
        view = null
    }

    private fun render(expanded: Boolean) {
        hide()
        if (lock?.isKeyguardLocked == true) return

        val edgeSize = dp(48)
        val screen = availableSize()
        if (screen.x < edgeSize || screen.y < edgeSize) return
        val travelX = screen.x - edgeSize
        val travelY = screen.y - edgeSize
        val anchorX = if (dockRight) (travelX - dp(4)).coerceAtLeast(0) else dp(4).coerceAtMost(travelX)
        val anchorY = (travelY * verticalPosition).roundToInt()
        val chipSize = dp(54)
        val halfChip = chipSize / 2
        val chips = if (expanded) {
            val shortcuts = actions.map {
                when (it) {
                    QuickShortcut.REMINDER -> DockAction("Remind", MainActivity.ACTION_NEW_REMINDER)
                    QuickShortcut.ALARM -> DockAction("Alarm", "alarm")
                    QuickShortcut.JOURNAL -> DockAction("Journal", MainActivity.ACTION_OPEN_JOURNAL)
                }
            } + DockAction("Note", MainActivity.ACTION_NEW_NOTE)
            val radius = dp(108)
            val angleBias = when {
                anchorY < radius -> 49
                travelY - anchorY < radius -> -49
                else -> 0
            }
            val direction = if (dockRight) -1 else 1
            shortcuts.mapIndexed { index, action ->
                val angle = Math.toRadians((angleBias - 45 + index * 30).toDouble())
                val x = anchorX + edgeSize / 2 + direction * (cos(angle) * radius).roundToInt()
                val y = anchorY + edgeSize / 2 + (sin(angle) * radius).roundToInt()
                Chip(action, Point(x.coerceIn(halfChip, (screen.x - halfChip).coerceAtLeast(halfChip)),
                    y.coerceIn(halfChip, (screen.y - halfChip).coerceAtLeast(halfChip))))
            }
        } else emptyList()

        val left = minOf(anchorX, chips.minOfOrNull { it.center.x - halfChip } ?: anchorX)
        val top = minOf(anchorY, chips.minOfOrNull { it.center.y - halfChip } ?: anchorY)
        val right = maxOf(anchorX + edgeSize, chips.maxOfOrNull { it.center.x - halfChip + chipSize } ?: 0)
        val bottom = maxOf(anchorY + edgeSize, chips.maxOfOrNull { it.center.y - halfChip + chipSize } ?: 0)
        val layout = FrameLayout(context)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val params = WindowManager.LayoutParams(right - left, bottom - top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.LEFT or Gravity.TOP
            x = left
            y = top
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        val anchor = ImageButton(context).apply {
            background = frostedTile(0x98687288.toInt(), 15)
            setImageResource(R.drawable.ic_monolog_m)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Monolog context"
            isHapticFeedbackEnabled = haptics
            setOnClickListener {
                if (expanded) render(false)
                else if (lock?.isKeyguardLocked != true) { hide(); capture() }
            }
            setOnLongClickListener { render(!expanded); true }

            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var dragging = false
            var held = false
            val open = Runnable {
                if (haptics) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                held = true
                render(!expanded)
            }
            fun settle() {
                if (dragging) {
                    dockRight = params.x + edgeSize / 2 >= screen.x / 2
                    verticalPosition = params.y.toFloat() / travelY.coerceAtLeast(1)
                    position.edit().putBoolean("right", dockRight).putFloat("vertical", verticalPosition).apply()
                    render(false)
                }
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        held = false
                        dragging = false
                        downX = event.rawX
                        downY = event.rawY
                        isPressed = true
                        postDelayed(open, holdMs)
                    }
                    MotionEvent.ACTION_MOVE -> if (!expanded && !held) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && hypot(dx, dy) > touchSlop) {
                            dragging = true
                            isPressed = false
                            removeCallbacks(open)
                        }
                        if (dragging) {
                            params.x = (anchorX + dx.roundToInt()).coerceIn(0, travelX)
                            params.y = (anchorY + dy.roundToInt()).coerceIn(0, travelY)
                            runCatching { windows.updateViewLayout(layout, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(open)
                        isPressed = false
                        if (dragging) settle()
                        else if (!held && event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(open)
                        isPressed = false
                        settle()
                    }
                }
                true
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    removeCallbacks(open)
                    dragging = false
                }
            })
        }
        layout.addView(anchor, FrameLayout.LayoutParams(edgeSize, edgeSize).apply {
            leftMargin = anchorX - left
            topMargin = anchorY - top
        })
        chips.forEach { chip ->
            val item = TextView(context).apply {
                text = chip.action.label
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(Color.WHITE)
                background = frostedTile(0xb4393f70.toInt(), 27)
                elevation = dp(2).toFloat()
                setOnClickListener { start(chip.action.intentAction) }
            }
            layout.addView(item, FrameLayout.LayoutParams(chipSize, chipSize).apply {
                leftMargin = chip.center.x - halfChip - left
                topMargin = chip.center.y - halfChip - top
            })
            item.alpha = 0f
            item.scaleX = .8f
            item.scaleY = .8f
            item.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(155).start()
        }
        if (expanded) layout.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_UP) {
                render(false)
                true
            } else event.actionMasked == MotionEvent.ACTION_DOWN
        }
        runCatching { windows.addView(layout, params) }.onSuccess { view = layout }
    }

    private fun availableSize(): Point {
        val metrics = windows.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Point(metrics.bounds.width() - insets.left - insets.right,
            metrics.bounds.height() - insets.top - insets.bottom)
    }

    private fun start(action: String) {
        render(false)
        val intent = if (action == "alarm") Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            else Intent(context, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "No clock app is available.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun frostedTile(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        setStroke(dp(1), 0x4affffff)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
