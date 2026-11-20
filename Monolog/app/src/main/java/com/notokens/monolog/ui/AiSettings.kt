package com.notokens.monolog.ui

import android.app.ActivityManager
import android.os.Build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AiSettings(settings: SettingsStore) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember(context) { ModelStore.get(context) }
    val state by store.state.collectAsState()
    val webEnabled by settings.webSuggestions.collectAsState(initial = false)
    val weatherCity by settings.weatherCity.collectAsState(initial = "")
    var city by remember(weatherCity) { mutableStateOf(weatherCity) }
    var nano by remember { mutableStateOf(emptyMap<NanoFeature, NanoAvailability>()) }
    var downloading by remember { mutableStateOf<NanoFeature?>(null) }
    var nanoMessage by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf("") }
    var showLanguages by remember { mutableStateOf(false) }
    val supportsLocal = remember { Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" } }
    val lowRam = remember { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }

    suspend fun refreshNano() {
        nano = NanoFeature.entries.associateWith { NanoAi.status(context, it) }
    }
    LaunchedEffect(Unit) { refreshNano() }

    Column(Modifier.fillMaxWidth().glassSurface(24).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("On-device AI models", style = MaterialTheme.typography.titleMedium)
        val recommendation = when {
            nano[NanoFeature.PROMPT] in setOf(NanoAvailability.READY, NanoAvailability.DOWNLOADABLE) ->
                "Try Gemini Nano first. Offline writing can serve as a fallback."
            !supportsLocal -> "Offline writing needs a supported 64-bit device."
            lowRam -> "Offline writing is optional. Run the speed check before relying on it."
            nano.isNotEmpty() -> "Suggested for this device: Offline writing."
            else -> "Checking on-device options…"
        }
        Text(recommendation, style = MaterialTheme.typography.bodySmall)
        Text("Offline writing · SmolLM2 135M · 143 MB")
        when (val current = state) {
            ModelState.Missing -> Button(store::download, enabled = supportsLocal) { Text("Download offline model") }
            ModelState.Ready -> {
                Text("Offline model ready")
                TextButton(enabled = !testing, onClick = {
                    testing = true
                    testMessage = ""
                    scope.launch {
                        try {
                            val elapsed = LocalAi.benchmark(context)
                            testMessage = if (elapsed == null) "The speed check timed out."
                            else "Sample rewrite took " + elapsed + " ms."
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            testMessage = "The offline model could not run on this device."
                        } finally {
                            testing = false
                        }
                    }
                }) { Text(if (testing) "Checking…" else "Run offline speed check") }
                if (testMessage.isNotBlank()) Text(testMessage)
            }
            is ModelState.Downloading -> Text("Downloading " + current.percent + "%")
            is ModelState.Failed -> {
                Text(current.message)
                TextButton(store::download) { Text("Retry download") }
            }
        }
        Text("Gemini Nano · managed by Android")
        for (feature in NanoFeature.entries) {
            val status = nano[feature]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(feature.label + " · " + (status?.label ?: "Checking…"), Modifier.weight(1f))
                if (status == NanoAvailability.DOWNLOADABLE) {
                    TextButton(enabled = downloading == null, onClick = {
                        downloading = feature
                        nanoMessage = ""
                        scope.launch {
                            try {
                                val result = NanoAi.download(context, feature)
                                nanoMessage = feature.label + " · " + result.label
                                refreshNano()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                nanoMessage = "Android could not finish this download."
                            } finally {
                                downloading = null
                            }
                        }
                    }) { Text(if (downloading == feature) "Downloading…" else "Download") }
                }
            }
        }
        TextButton({ scope.launch { refreshNano() } }, enabled = downloading == null) { Text("Refresh model status") }
        if (BuildConfig.DEBUG) TextButton({
            scope.launch { nanoMessage = "Foreground rewrite · " + NanoAi.probe(context) }
        }) { Text("Run Nano foreground test") }
        if (nanoMessage.isNotBlank()) Text(nanoMessage)
    }

    Column(Modifier.fillMaxWidth().glassSurface(24).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Web suggestions", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("While typing or using Vox", Modifier.weight(1f))
            Switch(webEnabled, { value -> scope.launch { settings.setWebSuggestions(value) } },
                modifier = Modifier.testTag("proactiveWebToggle"), enabled = WebLookup.configured)
        }
        Text("Recognized forecast questions can send only your chosen city and forecast date to Monolog’s search service.")
        if (!WebLookup.configured) Text("A search service has not been configured for this build.")
        if (webEnabled) {
            OutlinedTextField(city, { city = it.take(80) }, label = { Text("Weather city") }, singleLine = true)
            TextButton({ scope.launch { settings.setWeatherCity(city) } }) { Text("Save city") }
        }
    }

    Column(Modifier.fillMaxWidth().glassSurface(24).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton({ showLanguages = !showLanguages }) { Text("Languages & packs") }
        if (showLanguages) LanguageSettings(settings)
    }
}

@Composable
private fun LanguageSettings(settings: SettingsStore) {
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

    OutlinedTextField(filter, { filter = it }, label = { Text("Find a language") }, singleLine = true)
    for (language in KeyboardLanguage.all.filter { it.label.contains(filter.trim(), true) }) {
        val code = language.translationCode
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(language.label)
                val detail = when {
                    language.id == "en" -> "Built-in layout and rewrite"
                    language.id == "hi-Latn" -> "Roman keyboard and simple local polish"
                    code in installed -> "Native script and offline translation"
                    code != null -> "Native script · optional translation"
                    else -> "Native-script keyboard"
                }
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            TextButton({ scope.launch { settings.setKeyboardLanguage(language.id) } }) {
                Text(if (selected == language.id) "Active" else "Use")
            }
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
    Text("Offline translation uses Google ML Kit.", style = MaterialTheme.typography.bodySmall)
    if (message.isNotEmpty()) Text(message)
}
