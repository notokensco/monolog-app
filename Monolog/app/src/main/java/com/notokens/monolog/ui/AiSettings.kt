package com.notokens.monolog.ui

import android.app.ActivityManager
import android.os.Build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.notokens.monolog.BuildConfig
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.ai.LanguageResources
import com.notokens.monolog.ai.LocalAi
import com.notokens.monolog.ai.ModelState
import com.notokens.monolog.ai.ModelStore
import com.notokens.monolog.ai.NanoAi
import com.notokens.monolog.ai.NanoAvailability
import com.notokens.monolog.ai.NanoFeature
import com.notokens.monolog.ai.WebLookup
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.theme.PaperKeyText
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperPillBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glassSurface(24, GlassAccent.LAVENDER).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = PaperKeyText, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
internal fun AiSettings() {
    val context = LocalContext.current.applicationContext
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { ModelStore.get(context) }
    val state by store.state.collectAsState()
    val gemmaStatus by LocalAi.lastRunStatus.collectAsState()
    var nano by remember { mutableStateOf(emptyMap<NanoFeature, NanoAvailability>()) }
    var downloading by remember { mutableStateOf<NanoFeature?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf("") }
    val supportsLocal = remember { Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" } }
    val lowRam = remember { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }

    suspend fun refreshNano() {
        checking = true
        try {
            nano = NanoFeature.entries.associateWith { NanoAi.status(context, it) }
        } finally {
            checking = false
        }
    }
    LaunchedEffect(Unit) { refreshNano() }

    if (remember { com.notokens.monolog.ime.vox.OfflineWhisper.available }) {
        OfflineVoxSettings()
    }

    SettingsCard("Gemini Nano") {
        Text("Used automatically when available. Android manages its model downloads.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        NanoFeature.entries.forEachIndexed { index, feature ->
            if (index > 0) HorizontalDivider(color = PaperPillBorder)
            val status = nano[feature]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(feature.label, color = PaperKeyText, style = MaterialTheme.typography.titleSmall)
                    Text(status?.label ?: "Checking…", color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                }
                if (status == NanoAvailability.DOWNLOADABLE) {
                    TextButton(enabled = downloading == null, onClick = {
                        downloading = feature
                        statusMessage = ""
                        scope.launch {
                            try {
                                val result = NanoAi.download(context, feature)
                                statusMessage = "${feature.label} · ${result.label}"
                                refreshNano()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                statusMessage = "Android could not finish this download."
                            } finally {
                                downloading = null
                            }
                        }
                    }) { Text(if (downloading == feature) "Downloading…" else "Download") }
                }
            }
        }
        TextButton({ scope.launch { refreshNano() } }, enabled = !checking && downloading == null) {
            Text("Refresh availability")
        }
        if (statusMessage.isNotBlank()) Text(statusMessage, color = PaperKeySubText)
    }

    if (supportsLocal) {
        TextModelPicker(store)
        if (lowRam) Text("Larger text models may run slowly on this device.", color = PaperKeySubText)
    }

    if (BuildConfig.DEBUG || state is ModelState.Ready) {
        SettingsCard("Diagnostics") {
            if (BuildConfig.DEBUG) TextButton(enabled = !testing, onClick = {
                testing = true
                testMessage = ""
                scope.launch {
                    try { testMessage = "Nano foreground · ${NanoAi.probe(context)}" }
                    finally { testing = false }
                }
            }) { Text("Run Nano foreground test") }
            if (state is ModelState.Ready) TextButton(enabled = !testing, onClick = {
                testing = true
                testMessage = ""
                scope.launch {
                    try {
                        val elapsed = LocalAi.benchmark(context)
                        testMessage = elapsed?.let { "The sample answer took $it ms." } ?: "The speed check timed out."
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        testMessage = "The offline text model could not run on this device."
                    } finally {
                        testing = false
                    }
                }
            }) { Text("Run text model speed check") }
            gemmaStatus?.let { Text(it, color = PaperKeySubText, style = MaterialTheme.typography.bodySmall) }
            if (testing) Text("Running check…", color = PaperKeySubText)
            if (testMessage.isNotBlank()) Text(testMessage, color = PaperKeySubText)
        }
    }
}

@Composable
internal fun WebSettings(settings: SettingsStore) {
    val scope = rememberCoroutineScope()
    val webEnabled by settings.webSuggestions.collectAsState(initial = false)
    val webQuestions by settings.webQuestionSuggestions.collectAsState(initial = false)
    val weatherCity by settings.weatherCity.collectAsState(initial = "")
    var city by remember(weatherCity) { mutableStateOf(weatherCity) }
    SettingsCard("Web suggestions") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Live weather suggestions", Modifier.weight(1f), color = PaperKeyText)
            Switch(webEnabled, { value -> scope.launch { settings.setWebSuggestions(value) } },
                modifier = Modifier.testTag("proactiveWebToggle"), enabled = WebLookup.configured)
        }
        Text(if (WebLookup.configured) "Forecast suggestions send only your chosen city and date."
            else "Live forecasts need a configured weather service.", color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        if (webEnabled && WebLookup.configured) {
            OutlinedTextField(city, { city = it.take(80) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Weather city") }, singleLine = true)
            TextButton({ scope.launch { settings.setWeatherCity(city) } }) { Text("Save city") }
        }
        HorizontalDivider(color = PaperPillBorder)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Search typed questions", Modifier.weight(1f), color = PaperKeyText)
            Switch(webQuestions, { value -> scope.launch { settings.setWebQuestionSuggestions(value) } })
        }
        Text("When Web is on in Monolog AI, send recognized questions to the search provider for toolbar answers.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun LanguageSettings(settings: SettingsStore) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val selected by settings.keyboardLanguage.collectAsState(initial = "en")
    var installed by remember { mutableStateOf(emptySet<String>()) }
    var pending by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    suspend fun refresh() { installed = LanguageResources.installed(context) }
    LaunchedEffect(Unit) {
        try { refresh() } catch (error: CancellationException) { throw error }
        catch (_: Exception) { message = "Could not check offline translation packs." }
    }

    OutlinedTextField(filter, { filter = it }, modifier = Modifier.fillMaxWidth(),
        label = { Text("Find a language") }, singleLine = true)
    val languages = KeyboardLanguage.all.filter { it.label.contains(filter.trim(), true) }
    if (languages.isEmpty()) Text("No matching languages", color = PaperKeySubText)
    for (language in languages) {
        HorizontalDivider(color = PaperPillBorder)
        val code = language.translationCode
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(language.label, color = PaperKeyText, style = MaterialTheme.typography.titleSmall)
                val detail = when {
                    language.id == "en" -> "Built-in layout and rewrite"
                    language.id == "hi-Latn" -> "Roman keyboard and simple local polish"
                    code in installed -> "Native script and offline translation"
                    code != null -> "Native script · optional translation"
                    else -> "Native-script keyboard"
                }
                Text(detail, color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
            }
            if (selected == language.id) {
                Text("Active", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            } else TextButton({ scope.launch { settings.setKeyboardLanguage(language.id) } }) { Text("Use") }
        }
        if (code != null) TextButton(enabled = pending == null, onClick = {
            pending = language.id
            message = ""
            scope.launch {
                try {
                    if (code in installed) LanguageResources.remove(context, language)
                    else LanguageResources.download(context, language)
                    refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    message = "Could not update the language pack. Try again."
                } finally {
                    pending = null
                }
            }
        }) {
            Text(when {
                pending == language.id -> "Updating…"
                code in installed -> "Remove translation download"
                else -> "Download translation (~30 MB)"
            })
        }
    }
    Text("Translation packs run on this device.", color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
    if (message.isNotEmpty()) Text(message)
}

@Composable
internal fun OfflineVoxSettings(onReady: (Boolean) -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val whisper = com.notokens.monolog.ime.vox.OfflineWhisper
    var ready by remember { mutableStateOf(whisper.ready(context)) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ready) { onReady(ready) }
    SettingsCard("Offline Vox") {
        Text(
            if (ready) "Whisper Small is ready. Stop dictation or a voice note to transcribe it on this device."
            else "Download Whisper Small (190 MB) for offline dictation and voice notes.",
            color = PaperKeySubText
        )
        TextButton(enabled = !working, onClick = {
            working = true
            progress = 0
            message = null
            scope.launch {
                try {
                    if (ready) whisper.remove(context) else whisper.download(context) { progress = it }
                    ready = whisper.ready(context)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = if (ready) "Could not remove the model." else "Could not download and verify the model."
                } finally {
                    working = false
                }
            }
        }) { Text(if (working) "Working…" else if (ready) "Remove model" else "Download model") }
        if (working && !ready) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
