package com.notokens.monolog.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

enum class KeyboardAction {
    ENTER,
    DONE,
    GO,
    SEARCH,
    SEND,
    NEXT
}

/**
 * Encapsulates the resolved editor configuration for the active input field.
 * Guarantees that sensitive and password fields are strictly identified so
 * suggestions, logging, and external processing are prevented.
 */
data class EditorContext(
    val inputType: Int,
    val imeOptions: Int,
    val isPassword: Boolean,
    val isEmail: Boolean,
    val isUri: Boolean,
    val isNumber: Boolean,
    val isMultiline: Boolean,
    val action: KeyboardAction,
    val actionLabel: String?,
    val isNoPersonalizedLearning: Boolean,
    val packageName: String?,
    val isUserIncognito: Boolean = false,
    val isCredentialField: Boolean = false,
    val sensitiveDraft: Boolean = false
) {
    val isRestrictedField: Boolean
        get() = isPassword || isCredentialField
    val isIncognito: Boolean
        get() = isRestrictedField || isNoPersonalizedLearning || isUserIncognito || sensitiveDraft

    companion object {
        private val credentialHint = Regex(
            """\b(otp|one[ -]time (code|password|pin)|verification code|security code|passcode|cvv|upi pin)\b""",
            RegexOption.IGNORE_CASE
        )
        fun default(): EditorContext = EditorContext(
            inputType = InputType.TYPE_CLASS_TEXT,
            imeOptions = EditorInfo.IME_ACTION_NONE,
            isPassword = false,
            isEmail = false,
            isUri = false,
            isNumber = false,
            isMultiline = false,
            action = KeyboardAction.ENTER,
            actionLabel = null,
            isNoPersonalizedLearning = false,
            packageName = null
        )

        fun fromEditorInfo(info: EditorInfo?): EditorContext {
            if (info == null) return default()

            val inputType = info.inputType
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val inputVariation = inputType and InputType.TYPE_MASK_VARIATION

            val isPassword = when (inputClass) {
                InputType.TYPE_CLASS_TEXT -> {
                    inputVariation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        inputVariation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        inputVariation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                }
                InputType.TYPE_CLASS_NUMBER -> {
                    inputVariation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                }
                else -> false
            }

            val isEmail = inputClass == InputType.TYPE_CLASS_TEXT &&
                (inputVariation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    inputVariation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)

            val isUri = inputClass == InputType.TYPE_CLASS_TEXT &&
                inputVariation == InputType.TYPE_TEXT_VARIATION_URI

            val isNumber = inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME

            val isMultiline = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

            val imeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val action = if (isMultiline) {
                KeyboardAction.ENTER
            } else {
                when (imeAction) {
                    EditorInfo.IME_ACTION_DONE -> KeyboardAction.DONE
                    EditorInfo.IME_ACTION_GO -> KeyboardAction.GO
                    EditorInfo.IME_ACTION_SEARCH -> KeyboardAction.SEARCH
                    EditorInfo.IME_ACTION_SEND -> KeyboardAction.SEND
                    EditorInfo.IME_ACTION_NEXT -> KeyboardAction.NEXT
                    else -> KeyboardAction.ENTER
                }
            }

            val isNoPersonalized = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

            return EditorContext(
                inputType = inputType,
                imeOptions = info.imeOptions,
                isPassword = isPassword,
                isEmail = isEmail,
                isUri = isUri,
                isNumber = isNumber,
                isMultiline = isMultiline,
                action = action,
                actionLabel = info.actionLabel?.toString(),
                isNoPersonalizedLearning = isNoPersonalized,
                packageName = info.packageName,
                isCredentialField = info.hintText?.let(credentialHint::containsMatchIn) == true
            )
        }
    }

    /**
     * Executes the primary action (commit newline or trigger IME action).
     */
    fun performAction(inputConnection: InputConnection?) {
        if (inputConnection == null) return

        if (isMultiline) {
            inputConnection.commitText("\n", 1)
            return
        }

        val actionId = when (action) {
            KeyboardAction.DONE -> EditorInfo.IME_ACTION_DONE
            KeyboardAction.GO -> EditorInfo.IME_ACTION_GO
            KeyboardAction.SEARCH -> EditorInfo.IME_ACTION_SEARCH
            KeyboardAction.SEND -> EditorInfo.IME_ACTION_SEND
            KeyboardAction.NEXT -> EditorInfo.IME_ACTION_NEXT
            KeyboardAction.ENTER -> EditorInfo.IME_ACTION_NONE
        }

        if (actionId != EditorInfo.IME_ACTION_NONE) {
            inputConnection.performEditorAction(actionId)
        } else {
            // Send standard enter key event
            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }
}
