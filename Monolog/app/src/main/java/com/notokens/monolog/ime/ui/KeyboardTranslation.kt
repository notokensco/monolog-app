package com.notokens.monolog.ime.ui

import android.content.Context
import android.icu.text.BreakIterator
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import com.notokens.monolog.R
import com.notokens.monolog.ui.theme.LocalMonologDark
import com.google.mlkit.common.model.DownloadConditions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.notokens.monolog.ai.LocalMlKit
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

internal class KeyboardTranslation(
    private val context: Context,
    private val scope: CoroutineScope,
    private val editor: () -> InputConnection?,
    private val allowed: () -> Boolean
) {
    var active by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    private var translationJob: Job? = null
    private var client: Translator? = null
    private var connection: InputConnection? = null
    private var draft = ""
    private var rendered = ""
    private var composing = false

    fun prepare(from: String, to: String, ready: () -> Unit) {
        if (!allowed() || from == to || preparing) return
        close()
        val field = editor() ?: return
        preparing = true
        status = "Preparing translation languages…"
        translationJob = scope.launch {
            try {
                LocalMlKit.initialize(context)
                val translator = Translation.getClient(TranslatorOptions.Builder()
                    .setSourceLanguage(from).setTargetLanguage(to).build())
                client = translator
                translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
                if (!allowed() || editor() !== field) {
                    close()
                    return@launch
                }
                connection = field
                status = "${languageName(from)} → ${languageName(to)}"
                active = true
                ready()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                client?.close()
                client = null
                status = "Couldn’t prepare these languages. Try again."
            } finally {
                preparing = false
            }
        }
    }

    fun append(text: String): Boolean {
        if (!valid()) return false
        draft = (draft + text).take(800)
        update()
        return true
    }

    fun backspace(): Boolean {
        if (!valid()) return false
        if (draft.isEmpty()) return false
        val boundary = BreakIterator.getCharacterInstance().apply { setText(draft) }.preceding(draft.length)
        draft = draft.substring(0, boundary.coerceAtLeast(0))
        update()
        return true
    }

    fun finish(after: () -> Unit): Boolean {
        if (!valid()) return false
        val field = connection ?: return false
        if (draft.isEmpty()) {
            after()
        } else {
            update(immediate = true) {
                if (composing) field.finishComposingText()
                draft = ""
                rendered = ""
                composing = false
                after()
            }
        }
        return true
    }

    fun close() {
        translationJob?.cancel()
        if (composing) connection?.finishComposingText()
        client?.close()
        client = null
        connection = null
        draft = ""
        rendered = ""
        composing = false
        active = false
        preparing = false
        status = ""
    }

    private fun valid(): Boolean {
        if (!active) return false
        if (allowed() && editor() === connection) return true
        close()
        return false
    }

    private fun update(immediate: Boolean = false, done: (() -> Unit)? = null) {
        val field = connection ?: return
        val translator = client ?: return
        val text = draft
        translationJob?.cancel()
        translationJob = scope.launch {
            if (!immediate) delay(190)
            val result = try {
                if (text.isBlank()) text else translator.translate(text).await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status = "Translation couldn’t finish. Try again or close it."
                return@launch
            }
            if (!valid() || text != draft) return@launch
            if (result != rendered) {
                if (!field.setComposingText(result, 1)) {
                    status = "This field doesn’t support live translation."
                    return@launch
                }
                rendered = result
                composing = result.isNotEmpty()
            }
            done?.invoke()
        }
    }
}

private fun languageName(code: String) = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())

@Composable
internal fun KeyboardTranslationPicker(translation: KeyboardTranslation, onClose: () -> Unit) {
    val activeLanguage = LocalActiveKeyboardLanguage.current
    var source by remember { mutableStateOf(TranslateLanguage.fromLanguageTag(activeLanguage.speechTag) ?: "en") }
    var target by remember { mutableStateOf(if (source == "en") "hi" else "en") }
    var chooseSource by remember { mutableStateOf<Boolean?>(null) }
    val available = remember { TranslateLanguage.getAllLanguages().sortedBy(::languageName) }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        WorkspaceHeading("Translate", { if (translation.preparing) translation.close(); onClose() })
        Row {
            TextButton({ chooseSource = true }, enabled = !translation.preparing) { Text("From ${languageName(source)}") }
            TextButton({ chooseSource = false }, enabled = !translation.preparing) { Text("To ${languageName(target)}") }
        }
        val choosing = chooseSource
        if (choosing != null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                for (code in available) {
                    Text(languageName(code), Modifier.fillMaxWidth().clickable {
                        if (choosing) source = code else target = code
                        chooseSource = null
                    }.padding(8.dp), color = PaperKeyText)
                }
            }
        } else {
            Text(translation.status.ifEmpty { "Language packs may need Wi-Fi. Translation runs on your device." },
                color = PaperKeySubText)
            GoogleTranslationAttribution()
            TextButton({ translation.prepare(source, target, onClose) },
                enabled = source != target && !translation.preparing) { Text("Translate with Google") }
        }
    }
}

@Composable
internal fun GoogleTranslationAttribution() {
    val badge = if (LocalMonologDark.current) R.drawable.google_translate_attribution_white
        else R.drawable.google_translate_attribution_color
    Image(painterResource(badge), contentDescription = "Powered by Google Translate",
        modifier = Modifier.height(14.dp).widthIn(max = 165.dp))
}
