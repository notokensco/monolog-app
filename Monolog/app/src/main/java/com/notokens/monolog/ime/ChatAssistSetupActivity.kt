package com.notokens.monolog.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import com.notokens.monolog.ai.ModelState
import com.notokens.monolog.ai.ModelStore

internal class ChatAssistSetupActivity : ComponentActivity() {
    private var goal = ""
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = result.data
        val target = intent.getStringExtra(ChatAssistService.TARGET)
        if (result.resultCode == Activity.RESULT_OK && token != null && target != null && goal.isNotBlank()) {
            runCatching {
                startForegroundService(Intent(this, ChatAssistService::class.java)
                    .putExtra(ChatAssistService.TARGET, target)
                    .putExtra(ChatAssistService.GOAL, goal)
                    .putExtra(ChatAssistService.TOKEN, token))
            }.onFailure { ChatAssist.stop("Couldn’t start sharing. Try again from the chat.") }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val store = ModelStore.get(this)
        if (store.state.value !is ModelState.Ready || store.active.value?.isGguf != true ||
            intent.getStringExtra(ChatAssistService.TARGET).isNullOrBlank()) {
            ChatAssist.stop("Download Better or Best to use Chat Assist.")
            finish()
            return
        }
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val dialogTheme = if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            android.R.style.Theme_Material_Dialog_Alert
        } else android.R.style.Theme_Material_Light_Dialog_Alert
        val builder = AlertDialog.Builder(this, dialogTheme)
        val input = EditText(builder.context).apply {
            id = android.R.id.edit
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            hint = "Your goal for this chat"
            filters = arrayOf(android.text.InputFilter.LengthFilter(400))
            minLines = 2
            maxLines = 4
        }
        val margin = (24 * resources.displayMetrics.density).toInt()
        val field = FrameLayout(builder.context).apply {
            setPadding(margin, 0, margin, 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = builder.setTitle("Chat Assist")
            .setMessage("Tell Monolog what you want help with. Next, choose a single app in Android’s sharing dialog and select this chat app.")
            .setView(field)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Share chat app", null)
            .setOnCancelListener { finish() }
            .create()
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        dialog.show()
        val share = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        share.isEnabled = false
        input.doAfterTextChanged { share.isEnabled = !it.isNullOrBlank() }
        share.setOnClickListener {
            goal = input.text.toString().trim()
            if (goal.isBlank()) return@setOnClickListener
            dialog.dismiss()
            runCatching {
                picker.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }.onFailure {
                ChatAssist.stop("Couldn’t open Android’s sharing setup.")
                finish()
            }
        }
    }

}
