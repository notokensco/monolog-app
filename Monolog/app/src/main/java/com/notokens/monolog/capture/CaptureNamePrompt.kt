package com.notokens.monolog.capture

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Capture remains in memory until the user explicitly names and saves it.
// If the user doesn't type anything, Save remains disabled and the capture is canceled automatically if dismissed.
internal suspend fun requestCaptureName(context: Context, settings: SettingsStore): String? {
    val appearance = settings.appearance.first()
    val dark = appearance.theme == ThemeMode.INK || (appearance.theme == ThemeMode.SYSTEM &&
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    return suspendCancellableCoroutine { continuation ->
        val theme = if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        val builder = AlertDialog.Builder(context, theme)
        val density = context.resources.displayMetrics.density

        fun dp(value: Float): Int = (value * density + 0.5f).toInt()

        // Styled glass container
        val container = LinearLayout(builder.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(8f), dp(22f), dp(16f))
        }

        val subtitle = TextView(builder.context).apply {
            text = "Name your screenshot note to save it to your Context Library. Blank notes cannot be saved and will be canceled."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (dark) Color.parseColor("#A8A8B8") else Color.parseColor("#6B7088"))
            setPadding(0, 0, 0, dp(14f))
        }
        container.addView(subtitle)

        // Frosted input field styling
        val inputBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16f).toFloat()
            setColor(if (dark) Color.parseColor("#25262F") else Color.parseColor("#F0EDF9"))
            setStroke(dp(1.2f), if (dark) Color.parseColor("#3E3F4E") else Color.parseColor("#D5D1EB"))
        }

        val input = EditText(builder.context).apply {
            hint = "Note title..."
            setHintTextColor(if (dark) Color.parseColor("#787888") else Color.parseColor("#9B9EAF"))
            setTextColor(if (dark) Color.parseColor("#F5F5FA") else Color.parseColor("#14151D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            background = inputBg
            filters = arrayOf(InputFilter.LengthFilter(120))
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        container.addView(input)

        val handler = Handler(Looper.getMainLooper())
        val dialog = builder
            .setTitle("Save context capture")
            .setView(container)
            .setPositiveButton("Save note", null)
            .setNegativeButton("Cancel", null)
            .create()

        val dialogBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24f).toFloat()
            setColor(if (dark) Color.parseColor("#1C1D24") else Color.parseColor("#FFFFFF"))
            setStroke(dp(1f), if (dark) Color.parseColor("#383948") else Color.parseColor("#E2E0EF"))
        }
        dialog.window?.setBackgroundDrawable(dialogBg)

        val discardBlank = Runnable { if (input.text.isNullOrBlank()) dialog.cancel() }
        fun resetBlankTimeout() {
            handler.removeCallbacks(discardBlank)
            if (input.text.isNullOrBlank()) handler.postDelayed(discardBlank, 30_000)
        }

        dialog.setOnDismissListener {
            handler.removeCallbacks(discardBlank)
            if (continuation.isActive) continuation.resume(null)
        }

        if (context is AccessibilityService) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        }

        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

            save.isEnabled = false
            save.setTextColor(if (dark) Color.parseColor("#B5B3FF") else Color.parseColor("#4A57A4"))
            cancel.setTextColor(if (dark) Color.parseColor("#A8A8B8") else Color.parseColor("#6B7088"))

            input.doAfterTextChanged {
                val hasText = !it.isNullOrBlank()
                save.isEnabled = hasText
                save.alpha = if (hasText) 1f else 0.4f
                resetBlankTimeout()
            }
            save.alpha = 0.4f

            save.setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && continuation.isActive) {
                    continuation.resume(name)
                    dialog.dismiss()
                }
            }
            resetBlankTimeout()
        }

        continuation.invokeOnCancellation { handler.post { dialog.dismiss() } }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }
}
