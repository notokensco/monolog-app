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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.notokens.monolog.ai.CaptureVision
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.NotePhotoChanges
import com.notokens.monolog.data.PhotoCodec
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.theme.MonologTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

class CaptureAnnotationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val initialBitmap = PendingCaptureHolder.bitmap
        PendingCaptureHolder.bitmap = null
        if (initialBitmap == null) {
            finish()
            return
        }

        val settings = SettingsStore(applicationContext)

        setContent {
            val appearance by settings.appearance.collectAsState(initial = Appearance())
            val incognito by settings.incognito.collectAsState(initial = null)
            val language by settings.keyboardLanguage.collectAsState(initial = "en")
            LaunchedEffect(incognito) { if (incognito == true) finish() }
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
                    settings = settings,
                    speechTag = KeyboardLanguage.of(language).speechTag,
                    allowProcessing = incognito == false,
                    onDismiss = {
                        PendingCaptureHolder.bitmap = null
                        finish()
                    },
                    onSaved = {
                        PendingCaptureHolder.bitmap = null
                        Toast.makeText(this, "Saved to Journal", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }
}

private enum class CropHandle { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, INSIDE }

@Composable
private fun CaptureAnnotationScreen(
    sourceBitmap: Bitmap,
    accentColor: Color,
    settings: SettingsStore,
    speechTag: String,
    allowProcessing: Boolean,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var currentBitmap by remember { mutableStateOf(sourceBitmap) }
    var cropMode by remember { mutableStateOf(false) }
    var cropRect by remember { mutableStateOf(RectF(0.05f, 0.05f, 0.95f, 0.95f)) }
    var activeCropHandle by remember { mutableStateOf(CropHandle.NONE) }

    var bottomComment by remember { mutableStateOf("") }
    var isBottomVoice by remember { mutableStateOf(false) }
    var isRecordingBottomVoice by remember { mutableStateOf(false) }

    val tags = remember { mutableStateListOf<CaptureTag>() }
    var pendingTagLocation by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var editingTagIndex by remember { mutableStateOf<Int?>(null) }
    var tagInputText by remember { mutableStateOf("") }
    var isRecordingTagVoice by remember { mutableStateOf(false) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var naming by remember { mutableStateOf(false) }
    var noteName by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var answering by remember { mutableStateOf(false) }

    val recognizerOnDispose = rememberUpdatedState(activeRecognizer)
    DisposableEffect(Unit) {
        onDispose { recognizerOnDispose.value?.destroy() }
    }
    LaunchedEffect(naming, noteName.isBlank()) {
        if (naming && noteName.isBlank()) {
            delay(30_000)
            onDismiss()
        }
    }
    LaunchedEffect(asking, answering) {
        if (!asking || !answering) return@LaunchedEffect
        try {
            if (settings.incognito.first()) return@LaunchedEffect
            answer = CaptureVision.ask(context, currentBitmap, question.trim())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            answer = "No on-device answer available."
        } finally {
            answering = false
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Microphone permission required for voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    fun startVoiceRecognition(onResult: (String) -> Unit, onRecordingChange: (Boolean) -> Unit) {
        if (!allowProcessing || activeRecognizer != null) return
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
                activeRecognizer = null
                recognizer.destroy()
            }
            override fun onResults(results: Bundle?) {
                onRecordingChange(false)
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
        }
    }

    // Geometry of rendered bitmap inside container
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
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { cropMode = false }) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                        }
                        Button(
                            onClick = {
                                if (renderW > 0f && renderH > 0f) {
                                    val srcW = currentBitmap.width
                                    val srcH = currentBitmap.height
                                    val pxLeft = (cropRect.left * srcW).toInt().coerceIn(0, srcW - 2)
                                    val pxTop = (cropRect.top * srcH).toInt().coerceIn(0, srcH - 2)
                                    val pxWidth = ((cropRect.right - cropRect.left) * srcW).toInt().coerceIn(2, srcW - pxLeft)
                                    val pxHeight = ((cropRect.bottom - cropRect.top) * srcH).toInt().coerceIn(2, srcH - pxTop)
                                    try {
                                        val cropped = Bitmap.createBitmap(currentBitmap, pxLeft, pxTop, pxWidth, pxHeight)
                                        currentBitmap = cropped
                                        // Remap existing tags into the cropped coordinates
                                        val cLeft = cropRect.left
                                        val cTop = cropRect.top
                                        val cW = cropRect.width()
                                        val cH = cropRect.height()
                                        val remapped = tags.mapNotNull { tag ->
                                            if (tag.xRatio in cLeft..cropRect.right && tag.yRatio in cTop..cropRect.bottom) {
                                                tag.copy(
                                                    xRatio = ((tag.xRatio - cLeft) / cW).coerceIn(0.05f, 0.95f),
                                                    yRatio = ((tag.yRatio - cTop) / cH).coerceIn(0.05f, 0.95f)
                                                )
                                            } else null
                                        }
                                        tags.clear()
                                        tags.addAll(remapped)
                                        Toast.makeText(context, "Screenshot cropped", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {}
                                }
                                cropMode = false
                                cropRect = RectF(0.05f, 0.05f, 0.95f, 0.95f)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Text("Apply crop", color = Color.Black, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Text(
                        text = if (tags.size < 5) "Tap photo to add note (${tags.size}/5)" else "Max 5 notes added",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            TextButton(
                onClick = { asking = true },
                enabled = allowProcessing && !cropMode && !saving
            ) { Text("Ask about capture") }

            // Screenshot preview card with custom accent border
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .border(2.5.dp, accentColor, RoundedCornerShape(26.dp))
                    .background(Color(0xFF101014))
                    .onGloballyPositioned { containerSize = it.size }
                    .pointerInput(cropMode, tags.size, renderW, renderH, offsetX, offsetY) {
                        if (!cropMode) {
                            detectTapGestures { tapOffset ->
                                if (tags.size >= 5) {
                                    Toast.makeText(context, "Max 5 notes allowed", Toast.LENGTH_SHORT).show()
                                    return@detectTapGestures
                                }
                                if (renderW > 0f && renderH > 0f) {
                                    val relX = tapOffset.x - offsetX
                                    val relY = tapOffset.y - offsetY
                                    if (relX in 0f..renderW && relY in 0f..renderH) {
                                        val normX = (relX / renderW).coerceIn(0.05f, 0.95f)
                                        val normY = (relY / renderH).coerceIn(0.05f, 0.95f)
                                        pendingTagLocation = normX to normY
                                        tagInputText = ""
                                    }
                                }
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

                // Render placed tag bubbles
                if (!cropMode && renderW > 0f && renderH > 0f) {
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
                                color = Color(0xFF1E202B).copy(alpha = 0.95f),
                                border = androidx.compose.foundation.BorderStroke(1.2.dp, accentColor),
                                shadowElevation = 8.dp,
                                modifier = Modifier.clickable {
                                    editingTagIndex = index
                                    tagInputText = tag.text
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (tag.isVoice) {
                                        MonologIcon(Glyph.MIC, Modifier.size(13.dp), accentColor)
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

                // Smooth Manual Crop Overlay
                if (cropMode && renderW > 0f && renderH > 0f) {
                    val handleTouchSlopPx = with(density) { 44.dp.toPx() }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(renderW, renderH, offsetX, offsetY) {
                                detectDragGestures(
                                    onDragStart = { startPos ->
                                        val cl = offsetX + cropRect.left * renderW
                                        val ct = offsetY + cropRect.top * renderH
                                        val cr = offsetX + cropRect.right * renderW
                                        val cb = offsetY + cropRect.bottom * renderH

                                        val dTL = hypot(startPos.x - cl, startPos.y - ct)
                                        val dTR = hypot(startPos.x - cr, startPos.y - ct)
                                        val dBL = hypot(startPos.x - cl, startPos.y - cb)
                                        val dBR = hypot(startPos.x - cr, startPos.y - cb)

                                        activeCropHandle = when {
                                            dTL <= handleTouchSlopPx -> CropHandle.TOP_LEFT
                                            dTR <= handleTouchSlopPx -> CropHandle.TOP_RIGHT
                                            dBL <= handleTouchSlopPx -> CropHandle.BOTTOM_LEFT
                                            dBR <= handleTouchSlopPx -> CropHandle.BOTTOM_RIGHT
                                            startPos.x in cl..cr && startPos.y in ct..cb -> CropHandle.INSIDE
                                            else -> CropHandle.NONE
                                        }
                                    },
                                    onDragEnd = { activeCropHandle = CropHandle.NONE },
                                    onDragCancel = { activeCropHandle = CropHandle.NONE },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val dX = dragAmount.x / renderW
                                        val dY = dragAmount.y / renderH
                                        val minSize = 0.12f

                                        when (activeCropHandle) {
                                            CropHandle.TOP_LEFT -> {
                                                val nL = (cropRect.left + dX).coerceIn(0f, cropRect.right - minSize)
                                                val nT = (cropRect.top + dY).coerceIn(0f, cropRect.bottom - minSize)
                                                cropRect = RectF(nL, nT, cropRect.right, cropRect.bottom)
                                            }
                                            CropHandle.TOP_RIGHT -> {
                                                val nR = (cropRect.right + dX).coerceIn(cropRect.left + minSize, 1f)
                                                val nT = (cropRect.top + dY).coerceIn(0f, cropRect.bottom - minSize)
                                                cropRect = RectF(cropRect.left, nT, nR, cropRect.bottom)
                                            }
                                            CropHandle.BOTTOM_LEFT -> {
                                                val nL = (cropRect.left + dX).coerceIn(0f, cropRect.right - minSize)
                                                val nB = (cropRect.bottom + dY).coerceIn(cropRect.top + minSize, 1f)
                                                cropRect = RectF(nL, cropRect.top, cropRect.right, nB)
                                            }
                                            CropHandle.BOTTOM_RIGHT -> {
                                                val nR = (cropRect.right + dX).coerceIn(cropRect.left + minSize, 1f)
                                                val nB = (cropRect.bottom + dY).coerceIn(cropRect.top + minSize, 1f)
                                                cropRect = RectF(cropRect.left, cropRect.top, nR, nB)
                                            }
                                            CropHandle.INSIDE -> {
                                                val curW = cropRect.width()
                                                val curH = cropRect.height()
                                                var nL = cropRect.left + dX
                                                var nT = cropRect.top + dY
                                                if (nL < 0f) nL = 0f
                                                if (nL + curW > 1f) nL = 1f - curW
                                                if (nT < 0f) nT = 0f
                                                if (nT + curH > 1f) nT = 1f - curH
                                                cropRect = RectF(nL, nT, nL + curW, nT + curH)
                                            }
                                            CropHandle.NONE -> Unit
                                        }
                                    }
                                )
                            }
                    ) {
                        val cl = offsetX + cropRect.left * renderW
                        val ct = offsetY + cropRect.top * renderH
                        val cw = cropRect.width() * renderW
                        val ch = cropRect.height() * renderH
                        val cr = cl + cw
                        val cb = ct + ch

                        // Dark scrim over entire layout
                        drawRect(
                            color = Color.Black.copy(alpha = 0.65f),
                            size = size
                        )

                        // Clear cutout for active crop region
                        drawRect(
                            color = Color.Transparent,
                            topLeft = Offset(cl, ct),
                            size = Size(cw, ch),
                            blendMode = BlendMode.Clear
                        )

                        // Rule of thirds grid inside crop region
                        val gridColor = Color.White.copy(alpha = 0.28f)
                        val stroke1dp = 1.dp.toPx()
                        drawLine(gridColor, Offset(cl + cw / 3f, ct), Offset(cl + cw / 3f, cb), stroke1dp)
                        drawLine(gridColor, Offset(cl + 2f * cw / 3f, ct), Offset(cl + 2f * cw / 3f, cb), stroke1dp)
                        drawLine(gridColor, Offset(cl, ct + ch / 3f), Offset(cr, ct + ch / 3f), stroke1dp)
                        drawLine(gridColor, Offset(cl, ct + 2f * ch / 3f), Offset(cr, ct + 2f * ch / 3f), stroke1dp)

                        // Outer crop box border
                        drawRect(
                            color = accentColor,
                            topLeft = Offset(cl, ct),
                            size = Size(cw, ch),
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // L-shaped corner bracket handles
                        val bracketLen = 22.dp.toPx()
                        val bracketStroke = 4.dp.toPx()
                        val bracketColor = Color.White

                        // Top-Left
                        drawLine(bracketColor, Offset(cl, ct), Offset(cl + bracketLen, ct), bracketStroke)
                        drawLine(bracketColor, Offset(cl, ct), Offset(cl, ct + bracketLen), bracketStroke)

                        // Top-Right
                        drawLine(bracketColor, Offset(cr, ct), Offset(cr - bracketLen, ct), bracketStroke)
                        drawLine(bracketColor, Offset(cr, ct), Offset(cr, ct + bracketLen), bracketStroke)

                        // Bottom-Left
                        drawLine(bracketColor, Offset(cl, cb), Offset(cl + bracketLen, cb), bracketStroke)
                        drawLine(bracketColor, Offset(cl, cb), Offset(cl, cb - bracketLen), bracketStroke)

                        // Bottom-Right
                        drawLine(bracketColor, Offset(cr, cb), Offset(cr - bracketLen, cb), bracketStroke)
                        drawLine(bracketColor, Offset(cr, cb), Offset(cr, cb - bracketLen), bracketStroke)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bottom bar matching reference design:
            // Left: Crop icon. Middle: Note text. Right: Mic and yellow circle send button.
            if (!cropMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(60.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color(0xFF202127))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Manual crop button
                    IconButton(
                        onClick = {
                            cropMode = true
                            cropRect = RectF(0.05f, 0.05f, 0.95f, 0.95f)
                        }
                    ) {
                        MonologIcon(
                            Glyph.EXPAND,
                            Modifier.size(22.dp),
                            Color.White.copy(alpha = 0.85f)
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    // Middle text input
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = bottomComment,
                            onValueChange = {
                                bottomComment = it
                                isBottomVoice = false
                            },
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
                                onResult = {
                                    bottomComment = it
                                    isBottomVoice = true
                                },
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

                    val canSave = allowProcessing && !saving && activeRecognizer == null
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (canSave) accentColor else Color(0xFF353640))
                            .clickable(enabled = canSave, onClickLabel = "Name and save capture") { naming = true },
                        contentAlignment = Alignment.Center
                    ) {
                        MonologIcon(
                            Glyph.ARROW,
                            Modifier.size(20.dp),
                            if (canSave) Color.Black else Color.White.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                // In Crop Mode: bottom helper bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF202127))
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { cropRect = RectF(0.05f, 0.05f, 0.95f, 0.95f) }) {
                        Text("Reset frame", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                    Text(
                        "Drag corners or box to frame",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (naming) {
            AlertDialog(
                onDismissRequest = { if (!saving) onDismiss() },
                title = { Text("Name this capture") },
                text = {
                    Column {
                        Text("The screenshot and your notes stay on this device.")
                        OutlinedTextField(
                            value = noteName, onValueChange = { noteName = it.take(70) },
                            label = { Text("Note name") }, singleLine = true, enabled = !saving
                        )
                        if (saveError) Text("Capture could not be saved. Try again.")
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = allowProcessing && noteName.isNotBlank() && !saving,
                        onClick = {
                            saving = true
                            saveError = false
                            val bitmap = currentBitmap
                            val title = noteName.trim()
                            val comment = bottomComment.trim()
                            val annotations = tags.toList()
                            val commentIsVoice = isBottomVoice
                            scope.launch {
                                try {
                                    if (settings.incognito.first()) return@launch
                                    val vision = CaptureVision.inspect(context, bitmap)
                                    val evidence = vision.description()
                                    val category = buildCaptureEntry(vision.text.joinToString("\n") { it.text }).category
                                    val jpeg = withContext(Dispatchers.Default) { PhotoCodec.jpeg(bitmap) }
                                    if (settings.incognito.first()) return@launch
                                    val additionalBody = buildString {
                                        appendLine("**Category:** $category")
                                        if (evidence.isNotBlank()) {
                                            appendLine().appendLine(TagMetadataCodec.ANALYSIS_HEADING)
                                            evidence.lineSequence().forEach { line -> append("> ").appendLine(line) }
                                        }
                                    }
                                    val body = TagMetadataCodec.encodeCapture(annotations, commentIsVoice, comment, additionalBody)
                                    MonologData.repository(context).saveNote(
                                        title, body, photos = NotePhotoChanges(added = listOf(jpeg))
                                    )
                                    onSaved()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    saveError = true
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    ) { Text(if (saving) "Saving…" else "Save") }
                },
                dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("Discard") } }
            )
        }

        if (asking) {
            AlertDialog(
                onDismissRequest = { asking = false; question = ""; answer = null; answering = false },
                title = { Text("Ask about capture") },
                text = {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = question,
                            onValueChange = { question = it.take(240); answer = null },
                            label = { Text("Question") },
                            enabled = !answering
                        )
                        answer?.let {
                            Text(it + " *")
                            Text("* On-device results can miss details.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { answering = true },
                        enabled = allowProcessing && question.isNotBlank() && !answering
                    ) { Text(if (answering) "Working…" else "Ask on device") }
                },
                dismissButton = {
                    TextButton({ asking = false; question = ""; answer = null; answering = false }) { Text("Close") }
                }
            )
        }

        // Tap on screenshot: Popup to add a note/tag at that coordinate
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

        // Tap existing tag: Popup to edit or delete it
        editingTagIndex?.let { idx ->
            if (idx in tags.indices) {
                val tag = tags[idx]
                AlertDialog(
                    onDismissRequest = { editingTagIndex = null },
                    containerColor = Color(0xFF1E1F26),
                    title = {
                        Text(if (tag.isVoice) "Voice Tag" else "Text Tag", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    tags.removeAt(idx)
                                    editingTagIndex = null
                                }
                            ) {
                                Text("Delete", color = Color(0xFFFF5252))
                            }
                            Button(
                                onClick = {
                                    if (tagInputText.isNotBlank()) {
                                        tags[idx] = tag.copy(text = tagInputText)
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
    }
}
