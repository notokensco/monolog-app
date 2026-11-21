package com.notokens.monolog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.capture.CaptureTag
import com.notokens.monolog.capture.TagMetadataCodec
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.Note
import com.notokens.monolog.data.PhotoCodec
import com.notokens.monolog.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InteractiveCaptureViewer(
    note: Note,
    photoId: Long,
    onDismiss: () -> Unit,
    onSaveUpdated: (newTitle: String, newBody: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = remember { SettingsStore(context) }

    val language by settings.keyboardLanguage.collectAsState(initial = "en")
    val incognito by settings.incognito.collectAsState(initial = true)
    val borderColorHex by settings.captureBorderColor.collectAsState(initial = "#FFB800")
    val accentColor = remember(borderColorHex) {
        try {
            Color(android.graphics.Color.parseColor(borderColorHex))
        } catch (_: Exception) {
            Color(0xFFFFB800)
        }
    }

    // Decode tags and bottom comment from note body
    val decoded = remember(note.body) { TagMetadataCodec.decode(note.body) }
    val tags = remember { mutableStateListOf<CaptureTag>().apply { addAll(decoded.tags) } }
    var bottomComment by remember { mutableStateOf(decoded.bottomComment) }
    var isBottomVoice by remember { mutableStateOf(decoded.bottomIsVoice) }

    var hasChanges by remember { mutableStateOf(false) }

    // Load bitmap from repository
    var bitmap by remember(photoId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photoId) {
        withContext(Dispatchers.IO) {
            val bytes = MonologData.repository(context).photoImage(photoId)
            if (bytes != null) {
                bitmap = runCatching { PhotoCodec.decode(bytes, 2048) }.getOrNull()
            }
        }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var pendingTagLocation by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var editingTagIndex by remember { mutableStateOf<Int?>(null) }
    var tagInputText by remember { mutableStateOf("") }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var activeRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val recognizerOnDispose = rememberUpdatedState(activeRecognizer)
    DisposableEffect(Unit) {
        onDispose { recognizerOnDispose.value?.destroy() }
    }
    var editingBottomNote by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Microphone permission required for voice notes", Toast.LENGTH_SHORT).show()
    }

    fun startVoiceRecognition(onResult: (String) -> Unit) {
        if (incognito || activeRecognizer != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            Toast.makeText(context, "On-device voice recognition is unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: Exception) {
            Toast.makeText(context, "On-device voice recognition could not start", Toast.LENGTH_SHORT).show()
            return
        }
        activeRecognizer = recognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, KeyboardLanguage.of(language).speechTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isRecordingVoice = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isRecordingVoice = false }
            override fun onError(error: Int) {
                isRecordingVoice = false
                activeRecognizer = null
                recognizer.destroy()
            }
            override fun onResults(results: Bundle?) {
                isRecordingVoice = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                }
                activeRecognizer = null
                recognizer.destroy()
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            activeRecognizer = null
            recognizer.destroy()
            Toast.makeText(context, "On-device voice recognition could not start", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveAllChanges() {
        val newBody = TagMetadataCodec.encodeCapture(tags.toList(), isBottomVoice, bottomComment, decoded.additionalBody)
        onSaveUpdated(note.title, newBody)
        hasChanges = false
        Toast.makeText(context, "Memory updated", Toast.LENGTH_SHORT).show()
    }

    Dialog(
        onDismissRequest = {
            if (hasChanges) saveAllChanges()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .systemBarsPadding()
        ) {
            val previewHeight = (maxHeight * .82f).coerceIn(200.dp, 720.dp)
            Column(
                modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (hasChanges) saveAllChanges()
                        onDismiss()
                    }) {
                        MonologIcon(Glyph.CLOSE, Modifier.size(24.dp), Color.White)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = note.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (tags.isEmpty()) "Tap photo to add note" else "${tags.size}/5 notes pinned",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }

                    if (hasChanges) {
                        Button(
                            onClick = { saveAllChanges() },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Save", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }

                // Centered screenshot canvas
                val currentBitmap = bitmap
                if (currentBitmap != null) {
                    val imgW = currentBitmap.width.toFloat()
                    val imgH = currentBitmap.height.toFloat()
                    val boxW = containerSize.width.toFloat()
                    val boxH = containerSize.height.toFloat()

                    val (renderW, renderH, offsetX, offsetY) = remember(imgW, imgH, boxW, boxH) {
                        if (boxW <= 0f || boxH <= 0f || imgW <= 0f || imgH <= 0f) {
                            listOf(0f, 0f, 0f, 0f)
                        } else {
                            val imgAspect = imgW / imgH
                            val boxAspect = boxW / boxH
                            if (boxAspect > imgAspect) {
                                val h = boxH
                                val w = h * imgAspect
                                listOf(w, h, (boxW - w) / 2f, 0f)
                            } else {
                                val w = boxW
                                val h = w / imgAspect
                                listOf(w, h, 0f, (boxH - h) / 2f)
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .border(2.5.dp, accentColor, RoundedCornerShape(26.dp))
                            .background(Color(0xFF101014))
                            .onGloballyPositioned { containerSize = it.size }
                            .pointerInput(tags.size, renderW, renderH, offsetX, offsetY) {
                                detectTapGestures { tapOffset ->
                                    if (renderW > 0f && renderH > 0f) {
                                        val relX = tapOffset.x - offsetX
                                        val relY = tapOffset.y - offsetY
                                        if (relX in 0f..renderW && relY in 0f..renderH) {
                                            if (tags.size >= 5) {
                                                Toast.makeText(context, "Max 5 notes pinned", Toast.LENGTH_SHORT).show()
                                                return@detectTapGestures
                                            }
                                            val normX = (relX / renderW).coerceIn(0.05f, 0.95f)
                                            val normY = (relY / renderH).coerceIn(0.05f, 0.95f)
                                            pendingTagLocation = normX to normY
                                            tagInputText = ""
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = currentBitmap.asImageBitmap(),
                            contentDescription = "Screen memory",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // Render tags at exact relative coordinates
                        if (renderW > 0f && renderH > 0f) {
                            tags.forEachIndexed { index, tag ->
                                val pxX = offsetX + tag.xRatio * renderW
                                val pxY = offsetY + tag.yRatio * renderH
                                val dpX = with(density) { pxX.toDp() }
                                val dpY = with(density) { pxY.toDp() }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            start = (dpX - 16.dp).coerceAtLeast(8.dp),
                                            top = (dpY - 16.dp).coerceAtLeast(8.dp)
                                        )
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF1C1D24).copy(alpha = 0.94f),
                                        border = androidx.compose.foundation.BorderStroke(1.2.dp, accentColor),
                                        shadowElevation = 8.dp,
                                        modifier = Modifier.clickable {
                                            editingTagIndex = index
                                            tagInputText = tag.text
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (tag.isVoice) {
                                                MonologIcon(Glyph.MIC, Modifier.size(13.dp), accentColor)
                                                // Mini audio wave bars
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    repeat(3) { i ->
                                                        Box(
                                                            Modifier
                                                                .width(2.dp)
                                                                .height(if (i == 1) 10.dp else 6.dp)
                                                                .background(accentColor, RoundedCornerShape(1.dp))
                                                        )
                                                    }
                                                }
                                            } else {
                                                Box(
                                                    Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(accentColor)
                                                )
                                            }
                                            Text(
                                                text = tag.text,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .height(previewHeight)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = accentColor)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Bottom note card (Voice note or text note)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1E202A).copy(alpha = 0.92f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clickable { editingBottomNote = true }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isBottomVoice) {
                                    MonologIcon(Glyph.MIC, Modifier.size(16.dp), accentColor)
                                    Text(
                                        "Voice note",
                                        color = accentColor,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    // Audio waveform pill
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = accentColor.copy(alpha = 0.15f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            repeat(5) { i ->
                                                val h = when (i) {
                                                    0, 4 -> 4.dp
                                                    1, 3 -> 8.dp
                                                    else -> 12.dp
                                                }
                                                Box(
                                                    Modifier
                                                        .width(2.dp)
                                                        .height(h)
                                                        .background(accentColor, RoundedCornerShape(1.dp))
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    MonologIcon(Glyph.BOOK, Modifier.size(16.dp), Color.White.copy(alpha = 0.7f))
                                    Text(
                                        "Overall Note",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Text(
                                "Tap to edit",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 11.sp
                            )
                        }

                        if (bottomComment.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = bottomComment,
                                color = Color.White,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        } else {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "No overall note attached. Tap to add.",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Dialog: Add tag at pinned location
            if (pendingTagLocation != null) {
                AlertDialog(
                    onDismissRequest = { pendingTagLocation = null },
                    containerColor = Color(0xFF1E1F26),
                    title = {
                        Text("Add Note at Pin", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = tagInputText,
                                onValueChange = { tagInputText = it },
                                placeholder = { Text("Write note...", color = Color.White.copy(alpha = 0.4f)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = accentColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                                )
                            )

                            OutlinedButton(
                                onClick = {
                                    startVoiceRecognition {
                                        tagInputText = it
                                        val loc = pendingTagLocation
                                        if (loc != null && it.isNotBlank()) {
                                            tags.add(CaptureTag(loc.first, loc.second, it, isVoice = true))
                                            hasChanges = true
                                            pendingTagLocation = null
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (isRecordingVoice) accentColor else Color.White
                                )
                            ) {
                                MonologIcon(Glyph.MIC, Modifier.size(16.dp), if (isRecordingVoice) accentColor else Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text(if (isRecordingVoice) "Listening..." else "Dictate voice note")
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val loc = pendingTagLocation
                                if (loc != null && tagInputText.isNotBlank()) {
                                    tags.add(CaptureTag(loc.first, loc.second, tagInputText, isVoice = false))
                                    hasChanges = true
                                }
                                pendingTagLocation = null
                            },
                            enabled = tagInputText.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Text("Add Pin", color = Color.Black)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingTagLocation = null }) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                )
            }

            // Dialog: Edit existing tag
            editingTagIndex?.let { idx ->
                if (idx in tags.indices) {
                    val tag = tags[idx]
                    AlertDialog(
                        onDismissRequest = { editingTagIndex = null },
                        containerColor = Color(0xFF1E1F26),
                        title = {
                            Text(if (tag.isVoice) "Voice Note Tag" else "Text Tag", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = tagInputText,
                                    onValueChange = { tagInputText = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = accentColor,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                                    )
                                )

                                OutlinedButton(
                                    onClick = {
                                        startVoiceRecognition {
                                            tagInputText = it
                                        }
                                    }
                                ) {
                                    MonologIcon(Glyph.MIC, Modifier.size(16.dp), accentColor)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Re-dictate voice", color = Color.White)
                                }
                            }
                        },
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        tags.removeAt(idx)
                                        hasChanges = true
                                        editingTagIndex = null
                                    }
                                ) {
                                    Text("Delete", color = Color(0xFFFF5252))
                                }
                                Button(
                                    onClick = {
                                        if (tagInputText.isNotBlank()) {
                                            tags[idx] = tag.copy(text = tagInputText)
                                            hasChanges = true
                                        }
                                        editingTagIndex = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                                ) {
                                    Text("Save", color = Color.Black)
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { editingTagIndex = null }) {
                                Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    )
                }
            }

            // Dialog: Edit bottom overall note
            if (editingBottomNote) {
                var tempBottom by remember { mutableStateOf(bottomComment) }
                AlertDialog(
                    onDismissRequest = { editingBottomNote = false },
                    containerColor = Color(0xFF1E1F26),
                    title = {
                        Text("Edit Overall Note", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = tempBottom,
                                onValueChange = { tempBottom = it },
                                placeholder = { Text("Note description...", color = Color.White.copy(alpha = 0.4f)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = accentColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                                )
                            )

                            OutlinedButton(
                                onClick = {
                                    startVoiceRecognition {
                                        tempBottom = it
                                        isBottomVoice = true
                                    }
                                }
                            ) {
                                MonologIcon(Glyph.MIC, Modifier.size(16.dp), accentColor)
                                Spacer(Modifier.width(6.dp))
                                Text("Record Voice Note", color = Color.White)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                bottomComment = tempBottom
                                hasChanges = true
                                editingBottomNote = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Text("Update", color = Color.Black)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { editingBottomNote = false }) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                )
            }
        }
    }
}
