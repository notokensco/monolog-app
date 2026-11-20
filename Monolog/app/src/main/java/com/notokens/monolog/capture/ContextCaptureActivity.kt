package com.notokens.monolog.capture

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.NotePhotoChanges
import com.notokens.monolog.data.PhotoCodec
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.GlassAccent
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import com.notokens.monolog.ui.glassSurface
import com.notokens.monolog.ui.markdownExcerpt
import com.notokens.monolog.ui.monologBackdrop
import com.notokens.monolog.ui.theme.LocalMonologDark
import com.notokens.monolog.ui.theme.MonologTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ContextCaptureActivity : ComponentActivity() {
    private lateinit var sessionId: String
    private var captureStarted = false
    private var error by mutableStateOf<String?>(null)
    private val settings by lazy { SettingsStore(applicationContext) }
    private val repository by lazy { MonologData.repository(applicationContext) }

    private val consentRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val consent = result.data
        if (result.resultCode != Activity.RESULT_OK || consent == null) {
            finish()
        } else {
            lifecycleScope.launch {
                if (!captureAllowed()) {
                    error = "Screen capture is paused while incognito is on."
                } else {
                    try {
                        captureStarted = true
                        ProjectionCaptureService.start(this@ContextCaptureActivity, sessionId, consent)
                        watchForCapture()
                    } catch (_: RuntimeException) {
                        error = "Android couldn’t start screen capture."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sessionId = savedInstanceState?.getString("capture_session") ?: UUID.randomUUID().toString()
        captureStarted = savedInstanceState?.getBoolean("capture_started") == true

        setContent {
            val appearance by settings.appearance.collectAsState(initial = Appearance())
            val result by captureResults.collectAsState()
            val outcome = result?.takeIf { it.sessionId == sessionId }?.outcome
            MonologTheme(appearance = appearance) {
                val dark = LocalMonologDark.current
                if (outcome != null || error != null) {
                    SideEffect {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    CaptureScreen(onBack = ::finish) {
                        when (outcome) {
                            is CaptureOutcome.Photo -> CaptureReview(outcome.bitmap)
                            is CaptureOutcome.Failure -> Text(outcome.message)
                            null -> Text(error.orEmpty())
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null) lifecycleScope.launch {
            if (!captureAllowed()) {
                error = "Screen capture is paused while incognito is on."
            } else {
                try {
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    consentRequest.launch(manager.createScreenCaptureIntent())
                } catch (_: RuntimeException) {
                    error = "Android’s screen capture prompt is unavailable."
                }
            }
        }
        if (captureStarted) watchForCapture()
    }

    private fun watchForCapture() {
        lifecycleScope.launch {
            delay(8_000)
            if (captureResults.value?.sessionId != sessionId && error == null) {
                stopService(Intent(this@ContextCaptureActivity, ProjectionCaptureService::class.java))
                error = "This capture session ended. Hold Space to try again."
            }
        }
    }

    private suspend fun captureAllowed(): Boolean = try {
        !settings.incognito.first()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("capture_session", sessionId)
        outState.putBoolean("capture_started", captureStarted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopService(Intent(this, ProjectionCaptureService::class.java))
            if (captureResults.value?.sessionId == sessionId) captureResults.value = null
        }
        super.onDestroy()
    }

    @Composable
    private fun CaptureReview(bitmap: Bitmap) {
        val notes by repository.notes.collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        var title by rememberSaveable { mutableStateOf("") }
        var caption by rememberSaveable { mutableStateOf("") }
        var includeText by rememberSaveable { mutableStateOf(true) }
        var saving by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var webQuery by rememberSaveable { mutableStateOf<String?>(null) }
        val photo = remember(bitmap) { bitmap.asImageBitmap() }
        var extracted by remember(bitmap) { mutableStateOf<String?>(null) }
        LaunchedEffect(bitmap) {
            extracted = try {
                recognizeText(bitmap).take(20_000)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ""
            }
        }
        val text = extracted
        val related = remember(notes, text) {
            val terms = captureTerms(text.orEmpty())
            if (terms.isEmpty()) emptyList() else notes.mapNotNull { note ->
                val matches = captureTerms("${note.title} ${note.body}").intersect(terms)
                if (matches.size >= 2 || matches.any { it.length >= 6 }) note to matches.size else null
            }.sortedByDescending { it.second }.take(3).map { it.first }
        }

        Image(
            photo, "Captured screen", Modifier.fillMaxWidth().heightIn(max = 260.dp).glassSurface(20),
            contentScale = ContentScale.Fit
        )
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(caption, { caption = it }, label = { Text("What do you want to remember?") }, modifier = Modifier.fillMaxWidth())

        if (text == null) {
            Text("Reading text on-device…", style = MaterialTheme.typography.bodyMedium)
        } else if (text.isBlank()) {
            Text("No text recognised. You can still save the image and your note.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(Modifier.fillMaxWidth().glassSurface(20, GlassAccent.BLUE).padding(12.dp)) {
                Text("Text from the screenshot", style = MaterialTheme.typography.titleSmall)
                Text(text, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeText, onCheckedChange = { includeText = it })
                    Text("Keep the recognised text with the note")
                }
                TextButton(onClick = { webQuery = text.lineSequence().filter { it.isNotBlank() }.take(2).joinToString(" ").take(180) }) {
                    Text("Search web")
                }
            }
            Text("Related journal entries", style = MaterialTheme.typography.titleSmall)
            if (related.isEmpty()) Text("No related entries found.", style = MaterialTheme.typography.bodyMedium)
            related.forEach { note ->
                Column(Modifier.fillMaxWidth().glassSurface(16).padding(12.dp)) {
                    Text(note.title, style = MaterialTheme.typography.titleSmall)
                    Text(markdownExcerpt(note.body).take(120), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                saving = true
                message = null
                scope.launch {
                    try {
                        if (!captureAllowed()) {
                            message = "Turn off incognito to save a capture."
                            return@launch
                        }
                        val bytes = withContext(Dispatchers.IO) { PhotoCodec.jpeg(bitmap) }
                        val body = buildString {
                            append(caption)
                            if (includeText && !text.isNullOrBlank()) {
                                if (isNotBlank()) append("\n\n")
                                append("## Text from screenshot\n\n")
                                append(text.lineSequence().joinToString("\n") { "> " + it })
                            }
                        }
                        repository.saveNote(title.ifBlank { "Screen capture" }, body, photos = NotePhotoChanges(added = listOf(bytes)))
                        Toast.makeText(this@ContextCaptureActivity, "Saved to Journal", Toast.LENGTH_SHORT).show()
                        finish()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        message = "Couldn’t save the capture. Your draft is still here."
                    } finally {
                        saving = false
                    }
                }
            }
        ) {
            Text(if (saving) "Saving…" else "Save to journal")
        }

        webQuery?.let { query ->
            AlertDialog(
                onDismissRequest = { webQuery = null },
                title = { Text("Search the web") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Only the query below is sent to your search provider.")
                        OutlinedTextField(query, { webQuery = it }, label = { Text("Search query") })
                    }
                },
                confirmButton = {
                    TextButton(enabled = query.isNotBlank(), onClick = {
                        try {
                            startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query.trim()))
                            webQuery = null
                        } catch (_: ActivityNotFoundException) {
                            webQuery = null
                            message = "No web search app is available."
                        }
                    }) { Text("Search") }
                },
                dismissButton = { TextButton(onClick = { webQuery = null }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun CaptureScreen(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().monologBackdrop().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 624.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconControl(Glyph.BACK, "Discard capture", onBack, frosted = true)
                Text("Review screen capture", style = MaterialTheme.typography.titleLarge)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content
            )
        }
    }
}

private val wordPattern = Regex("[\\p{L}\\p{N}]{4,}")
private val commonWords = setOf("with", "this", "that", "from", "your", "have", "there", "they", "text", "screenshot", "about")

private fun captureTerms(text: String): Set<String> =
    wordPattern.findAll(text.lowercase()).map { it.value }.filterNot { it in commonWords }.toSet()

private suspend fun recognizeText(bitmap: Bitmap): String {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                .addOnCanceledListener { continuation.cancel() }
        }
    } finally {
        recognizer.close()
    }
}
