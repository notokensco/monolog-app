package com.notokens.monolog.ime.ui

import android.content.Context
import android.icu.text.BreakIterator
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.notokens.monolog.R
import com.notokens.monolog.ui.theme.LocalMonologDark
import com.google.mlkit.common.model.DownloadConditions
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.notokens.monolog.ai.LocalMlKit
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.glassSurface
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
    private var preparation = 0L
    private var client: Translator? = null
    private var connection: InputConnection? = null
    var draft by mutableStateOf("")
        private set
    private var rendered = ""
    private var composing = false

    fun prepare(from: String, to: String) {
        if (!allowed() || from == to || preparing || active) return
        val field = editor() ?: return
        if (connection !== field) close()
        connection = field
        val attempt = ++preparation
        preparing = true
        status = "Preparing language packs…"
        translationJob = scope.launch {
            try {
                LocalMlKit.initialize(context)
                val translator = Translation.getClient(TranslatorOptions.Builder()
                    .setSourceLanguage(from).setTargetLanguage(to).build())
                client = translator
                translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
                if (attempt != preparation) return@launch
                if (!allowed() || editor() !== field) {
                    close()
                    return@launch
                }
                status = ""
                active = true
                preparing = false
                if (draft.isNotEmpty()) update()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (attempt == preparation) {
                    client?.close()
                    client = null
                    status = "Couldn’t prepare language packs. Check Wi-Fi and retry."
                }
            } finally {
                if (attempt == preparation) preparing = false
            }
        }
    }

    fun append(text: String): Boolean {
        if (!hasEditor()) return false
        draft = (draft + text).take(800)
        update()
        return true
    }

    fun backspace(): Boolean {
        if (!hasEditor()) return false
        if (draft.isEmpty()) return true
        val boundary = BreakIterator.getCharacterInstance().apply { setText(draft) }.preceding(draft.length)
        draft = draft.substring(0, boundary.coerceAtLeast(0))
        update()
        return true
    }

    fun finish(after: () -> Unit): Boolean {
        if (!hasEditor()) return false
        if (!active) return true
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
        preparation++
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

    private fun hasEditor(): Boolean {
        val field = connection ?: return false
        if (allowed() && editor() === field) return true
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
            if (!active || !hasEditor() || text != draft) return@launch
            if (result != rendered) {
                if (!field.setComposingText(result, 1)) {
                    status = "This field doesn’t support live translation."
                    return@launch
                }
                rendered = result
                composing = result.isNotEmpty()
            }
            status = ""
            done?.invoke()
        }
    }
}

private fun languageName(code: String) = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())

@Composable
internal fun KeyboardTranslationBar(translation: KeyboardTranslation, onClose: () -> Unit) {
    val activeLanguage = LocalActiveKeyboardLanguage.current
    var source by remember { mutableStateOf(TranslateLanguage.fromLanguageTag(activeLanguage.speechTag) ?: "en") }
    var target by remember { mutableStateOf(if (source == "en") "hi" else "en") }
    val available = remember { TranslateLanguage.getAllLanguages().sortedBy(::languageName) }
    val canChoose = !translation.preparing && !translation.active
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp)
        .glassSurface(14).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TranslationLanguageMenu("Source language", source, target, available, canChoose,
                Modifier.weight(1f)) { source = it }
            TextButton(onClick = keyboardHapticAction {
                val original = source
                source = target
                target = original
            }, enabled = canChoose, modifier = Modifier.width(40.dp)
                .semantics { contentDescription = "Swap translation languages" },
                contentPadding = PaddingValues(0.dp)) {
                Text("⇄", style = MaterialTheme.typography.titleLarge)
            }
            TranslationLanguageMenu("Target language", target, source, available, canChoose,
                Modifier.weight(1f)) { target = it }
            IconControl(Glyph.CLOSE, "Close translation", keyboardHapticAction(action = onClose))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 6.dp, bottom = 3.dp)) {
                Text(
                    translation.status.ifEmpty {
                        if (translation.active) translation.draft.ifBlank { "Type to translate" }
                        else "Translate with Google"
                    },
                    color = if (translation.draft.isBlank()) PaperKeySubText else PaperKeyText,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoogleTranslationAttribution()
                    Text("On-device", color = PaperKeySubText, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            TextButton(
                onClick = keyboardHapticAction {
                    if (translation.active) translation.finish(onClose) else translation.prepare(source, target)
                },
                enabled = !translation.preparing && source != target,
                modifier = Modifier.semantics {
                    contentDescription = if (translation.active) "Finish translation" else "Translate with Google"
                }
            ) { Text("OK") }
        }
    }
}

@Composable
private fun TranslationLanguageMenu(
    label: String, code: String, other: String, available: List<String>, enabled: Boolean,
    modifier: Modifier, onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = keyboardHapticAction { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label, ${languageName(code)}" },
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(languageName(code), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
            if (enabled) MonologIcon(Glyph.DOWN, Modifier.size(13.dp), PaperKeySubText)
        }
        DropdownMenu(expanded && enabled, { expanded = false }, Modifier.heightIn(max = 220.dp),
            properties = PopupProperties(focusable = false)) {
            for (language in available) {
                DropdownMenuItem(text = { Text(languageName(language)) }, enabled = language != other,
                    onClick = keyboardHapticAction { expanded = false; onSelect(language) })
            }
        }
    }
}

@Composable
private fun GoogleTranslationAttribution() {
    val badge = if (LocalMonologDark.current) R.drawable.google_translate_attribution_white
        else R.drawable.google_translate_attribution_color
    Image(painterResource(badge), contentDescription = "Powered by Google Translate",
        modifier = Modifier.height(14.dp).widthIn(max = 165.dp))
}
