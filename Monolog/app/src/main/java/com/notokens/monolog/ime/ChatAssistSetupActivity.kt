package com.notokens.monolog.ime

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
        val margin = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin * 2, margin, margin)
        }
        layout.addView(TextView(this).apply { text = "Chat Assist"; textSize = 24f })
        layout.addView(TextView(this).apply {
            text = "Monolog reads visible chat text to suggest replies and feedback. "
                .plus("Choose A single app and select the chat app in Android’s sharing dialog. ")
                .plus("Session context stays on this device and is discarded when you stop.")
        })
        val input = EditText(this).apply {
            id = android.R.id.edit
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            hint = "What should Chat Assist help with?"
            filters = arrayOf(android.text.InputFilter.LengthFilter(400))
            setText(goal)
        }
        val share = Button(this).apply {
            text = "Choose chat app"
            isEnabled = goal.isNotBlank()
            setOnClickListener {
                goal = input.text.toString().trim()
                picker.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
        }
        input.doAfterTextChanged { share.isEnabled = !it.isNullOrBlank() }
        layout.addView(input)
        layout.addView(share)
        layout.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
        setContentView(layout)
    }

}
