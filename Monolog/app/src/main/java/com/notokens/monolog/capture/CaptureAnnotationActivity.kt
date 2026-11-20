package com.notokens.monolog.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.NotePhotoChanges
import com.notokens.monolog.data.PhotoCodec
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.theme.MonologTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureAnnotationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialBitmap = PendingCaptureHolder.bitmap
        if (initialBitmap == null) {
            finish()
            return
        }

        val settings = SettingsStore(applicationContext)

        setContent {
            val appearance by settings.appearance.collectAsState(initial = Appearance())
            val borderColorHex by settings.captureBorderColor.collectAsState(initial = "#FFB800")
            val borderColor = remember(borderColorHex) {
                try {
                    Color(android.graphics.Color.parseColor(borderColorHex))
                } catch (_: Exception) {
                    Color(0xFFFFB800)
                }
            }

            MonologTheme(appearance = appearance) {
                CaptureAnnotationScreen(
                    sourceBitmap = initialBitmap,
                    accentColor = borderColor,
                    onDismiss = {
                        PendingCaptureHolder.bitmap = null
                        finish()
                    },
                    onSaved = { noteTitle ->
                        PendingCaptureHolder.bitmap = null
                        Toast.makeText(this, "Saved to Journal: $noteTitle", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun CaptureAnnotationScreen(
    sourceBitmap: Bitmap,
    accentColor: Color,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentBitmap by remember { mutableStateOf(sourceBitmap) }
    var cropMode by remember { mutableStateOf(false) }
    var cropRectRatio by remember { mutableStateOf<RectF?>(null) }

    var bottomComment by remember { mutableStateOf("") }
    var isRecordingBottomVoice by remember { mutableStateOf(false) }

    val tags = remember { mutableStateListOf<CaptureTag>() }
    var pendingTagLocation by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var tagInputText by remember { mutableStateOf("") }
    var isRecordingTagVoice by remember { mutableStateOf(false) }

    var imageLayoutSize by remember { mutableStateOf(IntSize.Zero) }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Microphone permission required for voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    fun startVoiceRecognition(onResult: (String) -> Unit, onRecordingChange: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "Voice recognition not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onRecordingChange(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onRecordingChange(false) }
            override fun onError(error: Int) {
                onRecordingChange(false)
                recognizer.destroy()
            }
            override fun onResults(results: Bundle?) {
                onRecordingChange(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                }
                recognizer.destroy()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top control bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    MonologIcon(Glyph.CLOSE, Modifier.size(24.dp), Color.White)
                }

                if (cropMode) {
                    Button(
                        onClick = {
                            val rect = cropRectRatio
                            if (rect != null && rect.width() > 0.05f && rect.height() > 0.05f) {
                                val srcW = currentBitmap.width
                                val srcH = currentBitmap.height
                                val x = (rect.left * srcW).toInt().coerceIn(0, srcW - 1)
                                val y = (rect.top * srcH).toInt().coerceIn(0, srcH - 1)
                                val w = ((rect.right - rect.left) * srcW).toInt().coerceIn(1, srcW - x)
                                val h = ((rect.bottom - rect.top) * srcH).toInt().coerceIn(1, srcH - y)
                                try {
                                    val cropped = Bitmap.createBitmap(currentBitmap, x, y, w, h)
                                    currentBitmap = cropped
                                    tags.clear() // clear tags that belonged to full coordinate space
                                } catch (_: Exception) {}
                            }
                            cropMode = false
                            cropRectRatio = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text("Apply crop", color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(
                        text = if (tags.size < 5) "Tap photo to add note (${tags.size}/5)" else "Max 5 notes added",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
            }

            // Screenshot preview card with border
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .border(2.5.dp, accentColor, RoundedCornerShape(26.dp))
                    .background(Color(0xFF141416))
                    .onGloballyPositioned { imageLayoutSize = it.size }
                    .pointerInput(cropMode, tags.size) {
                        detectTapGestures { offset ->
                            if (!cropMode && tags.size < 5 && imageLayoutSize.width > 0 && imageLayoutSize.height > 0) {
                                val xR = (offset.x / imageLayoutSize.width).coerceIn(0.05f, 0.95f)
                                val yR = (offset.y / imageLayoutSize.height).coerceIn(0.05f, 0.95f)
                                pendingTagLocation = xR to yR
                                tagInputText = ""
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = "Screen capture",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                // Crop handles / overlay
                if (cropMode) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val left = w * 0.1f
                        val top = h * 0.1f
                        val right = w * 0.9f
                        val bottom = h * 0.9f

                        if (cropRectRatio == null) {
                            cropRectRatio = RectF(0.1f, 0.1f, 0.9f, 0.9f)
                        }

                        drawRect(
                            color = Color.Black.copy(alpha = 0.5f),
                            size = Size(w, h)
                        )
                        // Cutout active crop
                        drawRect(
                            color = Color.Transparent,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            blendMode = androidx.compose.ui.graphics.BlendMode.Clear
                        )
                        drawRect(
                            color = accentColor,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                        )
                    }
                }

                // Render placed tag bubbles
                tags.forEachIndexed { index, tag ->
                    val xPos = (tag.xRatio * imageLayoutSize.width).dp - 12.dp
                    val yPos = (tag.yRatio * imageLayoutSize.height).dp - 12.dp

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = (tag.xRatio * with(LocalDensity.current) { imageLayoutSize.width.toDp().value }).dp.coerceAtLeast(8.dp),
                                top = (tag.yRatio * with(LocalDensity.current) { imageLayoutSize.height.toDp().value }).dp.coerceAtLeast(8.dp)
                            )
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF22232A).copy(alpha = 0.92f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.8f)),
                            shadowElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (tag.isVoice) {
                                    MonologIcon(Glyph.MIC, Modifier.size(12.dp), accentColor)
                                } else {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(accentColor)
                                    )
                                }
                                Text(
                                    text = tag.text,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bottom bar matching user specification:
            // Crop icon on left, text in middle, voice and send on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color(0xFF23242A))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Crop button
                IconButton(
                    onClick = {
                        cropMode = !cropMode
                        if (!cropMode) cropRectRatio = null
                    }
                ) {
                    MonologIcon(
                        Glyph.EXPAND,
                        Modifier.size(22.dp),
                        if (cropMode) accentColor else Color.White.copy(alpha = 0.75f)
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Middle comment input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = bottomComment,
                        onValueChange = { bottomComment = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(accentColor),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (bottomComment.isEmpty()) {
                                Text(
                                    "Add a note...",
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 15.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                // Voice dictation button for main comment
                IconButton(
                    onClick = {
                        startVoiceRecognition(
                            onResult = { bottomComment = it },
                            onRecordingChange = { isRecordingBottomVoice = it }
                        )
                    }
                ) {
                    MonologIcon(
                        Glyph.MIC,
                        Modifier.size(22.dp),
                        if (isRecordingBottomVoice) accentColor else Color.White.copy(alpha = 0.85f)
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Send / Save button (circular accent colored button)
                val canSave = bottomComment.isNotBlank() || tags.isNotEmpty()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (canSave) accentColor else Color(0xFF383944))
                        .clickable(enabled = canSave) {
                            scope.launch {
                                val repo = MonologData.repository(context)
                                val jpegBytes = withContext(Dispatchers.Default) {
                                    PhotoCodec.jpeg(currentBitmap)
                                }

                                val noteTitle = when {
                                    bottomComment.isNotBlank() -> bottomComment.take(60)
                                    tags.isNotEmpty() -> tags.first().text.take(60)
                                    else -> "Capture Note"
                                }

                                val noteBody = buildString {
                                    if (bottomComment.isNotBlank()) {
                                        append(bottomComment)
                                        append("\n\n")
                                    }
                                    if (tags.isNotEmpty()) {
                                        append("### Annotated Notes\n")
                                        tags.forEachIndexed { i, t ->
                                            val type = if (t.isVoice) "🎤 Voice note" else "💬 Tag"
                                            append("- **$type:** ${t.text}\n")
                                        }
                                    }
                                }

                                repo.saveNote(
                                    title = noteTitle,
                                    body = noteBody,
                                    photos = NotePhotoChanges(added = listOf(jpegBytes))
                                )
                                onSaved(noteTitle)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    MonologIcon(Glyph.ARROW, Modifier.size(20.dp), if (canSave) Color.Black else Color.White.copy(alpha = 0.4f))
                }
            }
        }

        // Tap on screenshot: Popup to add a note/tag at that point
        if (pendingTagLocation != null) {
            AlertDialog(
                onDismissRequest = { pendingTagLocation = null },
                containerColor = Color(0xFF1E1F26),
                title = {
                    Text("Pin Note to Screen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Add a quick text or voice tag at this point.", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)

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

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    startVoiceRecognition(
                                        onResult = {
                                            tagInputText = it
                                            val loc = pendingTagLocation
                                            if (loc != null && it.isNotBlank()) {
                                                tags.add(CaptureTag(loc.first, loc.second, it, isVoice = true))
                                                pendingTagLocation = null
                                            }
                                        },
                                        onRecordingChange = { isRecordingTagVoice = it }
                                    )
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (isRecordingTagVoice) accentColor else Color.White
                                )
                            ) {
                                MonologIcon(Glyph.MIC, Modifier.size(16.dp), if (isRecordingTagVoice) accentColor else Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text(if (isRecordingTagVoice) "Listening..." else "Voice note")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val loc = pendingTagLocation
                            if (loc != null && tagInputText.isNotBlank()) {
                                tags.add(CaptureTag(loc.first, loc.second, tagInputText, isVoice = false))
                            }
                            pendingTagLocation = null
                        },
                        enabled = tagInputText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text("Add Tag", color = Color.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingTagLocation = null }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                    }
                }
            )
        }
    }
}
