package com.notokens.monolog.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notokens.monolog.capture.CaptureTag
import com.notokens.monolog.capture.CaptureAnnotationActivity
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
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val settings = remember { SettingsStore(context) }

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
    var bottomAudio by remember { mutableStateOf(decoded.bottomAudio) }
    var isBottomVoice by remember { mutableStateOf(decoded.bottomIsVoice) }

    var hasChanges by remember { mutableStateOf(false) }

    // Load bitmap from repository
    var bitmap by remember(photoId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photoId, note.updatedAt) {
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
    var editingBottomNote by remember { mutableStateOf(false) }
    LaunchedEffect(pendingTagLocation, editingTagIndex, editingBottomNote) {
        if (pendingTagLocation == null && editingTagIndex == null && !editingBottomNote) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Microphone permission required for voice notes", Toast.LENGTH_SHORT).show()
    }


    fun saveAllChanges() {
        val newBody = TagMetadataCodec.encodeCapture(tags.toList(), isBottomVoice, bottomComment, decoded.additionalBody, bottomAudio)
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
                .background(Color(0xFF101014))
                .imePadding()
                .systemBarsPadding()
        ) {
            val previewHeight = (maxHeight - 180.dp).coerceIn(140.dp, 660.dp)
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll)
                    .padding(bottom = 12.dp),
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

                    Column(Modifier.weight(1f).padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
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
                        TextButton(onClick = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }) {
                            Text("Overall note", color = accentColor, fontSize = 12.sp)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = bitmap != null && !incognito, onClick = {
                            focus.clearFocus(force = true)
                            keyboard?.hide()
                            try {
                                val body = TagMetadataCodec.encodeCapture(tags.toList(), isBottomVoice, bottomComment, decoded.additionalBody, bottomAudio)
                                CaptureAnnotationActivity.openCrop(context, note.copy(body = body), photoId)
                                onDismiss()
                            } catch (_: Exception) {
                                Toast.makeText(context, "Could not open the crop editor", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            MonologIcon(Glyph.EXPAND, Modifier.size(18.dp), Color.White)
                            Spacer(Modifier.width(5.dp))
                            Text("Crop", color = Color.White)
                        }
                        if (hasChanges) {
                            Button(
                                onClick = { saveAllChanges() },
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Save", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
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
                CaptureNoteSheet(
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

                            VoiceNoteControl(enabled = !incognito, onPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                onVoice = { voice ->
                                    val location = pendingTagLocation ?: return@VoiceNoteControl
                                    tags.add(CaptureTag(location.first, location.second, voice.transcript.ifBlank { "Voice note" },
                                        isVoice = true, audio = voice.audio))
                                    hasChanges = true
                                    pendingTagLocation = null
                                })

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
                    CaptureNoteSheet(
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

                                tag.audio?.let { VoiceNotePlayback(it) }
                                VoiceNoteControl(enabled = !incognito, onPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    onVoice = { voice ->
                                        tags[idx] = tag.copy(audio = voice.audio, isVoice = true,
                                            text = voice.transcript.ifBlank { tagInputText.ifBlank { "Voice note" } })
                                        tagInputText = tags[idx].text
                                        hasChanges = true
                                    })

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
                var tempAudio by remember { mutableStateOf(bottomAudio) }
                var tempIsVoice by remember { mutableStateOf(isBottomVoice) }
                CaptureNoteSheet(
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

                            tempAudio?.let { VoiceNotePlayback(it) }
                            VoiceNoteControl(enabled = !incognito, onPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                onVoice = { voice ->
                                    tempAudio = voice.audio
                                    if (voice.transcript.isNotBlank()) tempBottom = voice.transcript
                                    tempIsVoice = true
                                })

                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                bottomComment = tempBottom
                                bottomAudio = tempAudio
                                isBottomVoice = tempIsVoice
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
