package com.notokens.monolog.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorContextTest {

    @Test
    fun testDefaultEditorContext() {
        val context = EditorContext.default()
        assertFalse(context.isPassword)
        assertFalse(context.isIncognito)
        assertFalse(context.isEmail)
        assertFalse(context.isUri)
        assertFalse(context.isNumber)
        assertFalse(context.isMultiline)
        assertEquals(KeyboardAction.ENTER, context.action)
    }

    @Test
    fun testPasswordEditorInfoTriggersIncognito() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val context = EditorContext.fromEditorInfo(info)
        assertTrue(context.isPassword)
        assertTrue(context.isIncognito)

        val webPasswordInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        val webContext = EditorContext.fromEditorInfo(webPasswordInfo)
        assertTrue(webContext.isPassword)
        assertTrue(webContext.isIncognito)

        val numberPasswordInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val numberContext = EditorContext.fromEditorInfo(numberPasswordInfo)
        assertTrue(numberContext.isPassword)
        assertTrue(numberContext.isIncognito)
    }

    @Test
    fun testNoPersonalizedLearningTriggersIncognito() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val context = EditorContext.fromEditorInfo(info)
        assertFalse(context.isPassword)
        assertTrue(context.isNoPersonalizedLearning)
        assertTrue(context.isIncognito)
    }

    @Test
    fun testEmailAndUriVariations() {
        val emailInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val emailContext = EditorContext.fromEditorInfo(emailInfo)
        assertTrue(emailContext.isEmail)
        assertFalse(emailContext.isUri)

        val uriInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val uriContext = EditorContext.fromEditorInfo(uriInfo)
        assertTrue(uriContext.isUri)
        assertFalse(uriContext.isEmail)
    }

    @Test
    fun testNumberTypes() {
        val numInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val numContext = EditorContext.fromEditorInfo(numInfo)
        assertTrue(numContext.isNumber)

        val phoneInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val phoneContext = EditorContext.fromEditorInfo(phoneInfo)
        assertTrue(phoneContext.isNumber)
    }

    @Test
    fun testMultilineEditorForcesEnterAction() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val context = EditorContext.fromEditorInfo(info)
        assertTrue(context.isMultiline)
        assertEquals(KeyboardAction.ENTER, context.action)
    }

    @Test
    fun testSingleLineEditorResolvesAction() {
        val searchInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        assertEquals(KeyboardAction.SEARCH, EditorContext.fromEditorInfo(searchInfo).action)

        val sendInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        assertEquals(KeyboardAction.SEND, EditorContext.fromEditorInfo(sendInfo).action)

        val doneInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        assertEquals(KeyboardAction.DONE, EditorContext.fromEditorInfo(doneInfo).action)

        val goInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        assertEquals(KeyboardAction.GO, EditorContext.fromEditorInfo(goInfo).action)

        val nextInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        assertEquals(KeyboardAction.NEXT, EditorContext.fromEditorInfo(nextInfo).action)
    }
}
