package com.notokens.monolog.ime.vox

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import com.notokens.monolog.ime.ui.ContextKey
import com.notokens.monolog.ime.ui.KeyButton
import com.notokens.monolog.ime.ui.triggerHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.ui.GlassAccent
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.IconControl
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.MonologLogo
import com.notokens.monolog.ui.glassSurface
import com.notokens.monolog.ui.theme.PaperAccentBlue
import com.notokens.monolog.ui.theme.PaperAccentLavender
import com.notokens.monolog.ui.theme.PaperAccentLavenderBorder
import com.notokens.monolog.ui.theme.MonologIncognitoShield
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import com.notokens.monolog.ui.theme.PaperPillBackground

@Composable
fun VoxPanel(
    voxState: VoxState,
    language: String,
    languages: List<VoxLanguage>,
    onLanguage: (VoxLanguage) -> Unit,
    onStartListening: () -> Unit,
    onPauseListening: () -> Unit,
    onDeleteWord: () -> Unit,
    personal: Boolean = false,
    audioRecording: Boolean = false,
    savingSpokenNote: Boolean = false,
    savingRemember: Boolean = false,
    privateActions: Boolean = false,
    onTogglePersonal: () -> Unit = {},
    onAudioNote: () -> Unit = {},
    smartFilter: Boolean,
    onToggleSmartFilter: () -> Unit,
    contextEnabled: Boolean,
    contextActive: Boolean,
    onContext: () -> Unit,
    onContextHold: () -> Unit,
    onCancel: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenModels: () -> Unit = {},
    onMiniBounds: (android.graphics.Rect?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val fullTranscript = listOf(voxState.transcript, voxState.partialTranscript)
        .filter(String::isNotBlank).joinToString(" ")
    val isListening = voxState.status == VoxStatus.LISTENING
    val preparing = voxState.status == VoxStatus.PREPARING
    val playDescription = when {
        !isListening -> "Start listening"
        audioRecording -> "Save audio note"
        savingRemember -> "Save to Remember"
        savingSpokenNote -> "Save dictated note"
        personal -> "Search journal and library"
        else -> "Pause listening"
    }
    var languagesOpen by remember { mutableStateOf(false) }
    val view = LocalView.current
    val vibration = com.notokens.monolog.ime.ui.LocalVibrationHaptics.current
    var minimized by remember { mutableStateOf(false) }
    if (minimized) {
        val boundsChanged by rememberUpdatedState(onMiniBounds)
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { boundsChanged(null) } }
        BoxWithConstraints(Modifier.fillMaxWidth().height(134.dp), contentAlignment = Alignment.BottomCenter) {
            val density = LocalDensity.current
            val width = (maxWidth - 16.dp).coerceAtMost(390.dp).coerceAtLeast(1.dp)
            val limitX = with(density) { ((maxWidth - width) / 2).toPx() }
            val limitY = with(density) { 72.dp.toPx() }
            var position by remember { mutableStateOf(Offset.Zero) }
            Row(Modifier.offset {
                IntOffset(position.x.coerceIn(-limitX, limitX).roundToInt(),
                    position.y.coerceIn(-limitY, 0f).roundToInt())
            }.width(width).height(56.dp).onGloballyPositioned {
                val r = it.boundsInWindow()
                boundsChanged(android.graphics.Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
            }.glassSurface(18, GlassAccent.LAVENDER).padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconControl(Glyph.EXPAND, "Expand Vox", {
                    triggerHapticFeedback(view, enabled = vibration)
                    minimized = false
                })
                Box(Modifier.weight(1f).pointerInput(limitX, limitY) {
                    detectDragGestures { change, movement ->
                        change.consume()
                        position = Offset((position.x + movement.x).coerceIn(-limitX, limitX),
                            (position.y + movement.y).coerceIn(-limitY, 0f))
                    }
                }.semantics { contentDescription = "Drag Vox bar" }) {
                    VoxTranscriptStage(fullTranscript, isListening, voxState.rmsLevel, compact = true)
                }
                Text(when {
                    preparing -> "…"
                    isListening -> "❚❚"
                    else -> "▶"
                }, Modifier.clip(RoundedCornerShape(10.dp)).clickable(
                    enabled = !preparing && voxState.status != VoxStatus.PROCESSING
                ) {
                    triggerHapticFeedback(view, enabled = vibration)
                    if (voxState.status == VoxStatus.PERMISSION_REQUIRED) onRequestPermission()
                    else if (isListening) onPauseListening() else onStartListening()
                }.semantics { contentDescription = playDescription }
                    .padding(10.dp), color = PaperAccentBlue, fontSize = 17.sp)
                ContextKey(contextEnabled, contextActive, onContext, onContextHold)
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .glassSurface(24, GlassAccent.LAVENDER)
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * .47f).dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) {
                IconControl(Glyph.BACK, "Return to keyboard", onCancel, frosted = true)
            }
            Text("VOX", Modifier.align(Alignment.Center), color = PaperKeyText, fontSize = 22.sp,
                fontFamily = VoxWordmarkFont,
                fontWeight = FontWeight.SemiBold)
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                IconControl(Glyph.DOUBLE_DOWN, "Minimize Vox", {
                    triggerHapticFeedback(view, enabled = vibration)
                    languagesOpen = false
                    minimized = true
                })
                ContextKey(contextEnabled, contextActive, onContext, onContextHold)
            }
        }

        if (privateActions || personal || audioRecording) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                androidx.compose.material3.FilterChip(personal, onTogglePersonal, { Text("Personal") },
                    enabled = privateActions && !savingSpokenNote && !audioRecording)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.FilterChip(audioRecording, onAudioNote,
                    { Text(if (audioRecording) "Save audio note" else "Audio Note") },
                    enabled = (privateActions || audioRecording) && !savingSpokenNote)
            }
        }

        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // State indicator
            when (voxState.status) {
                VoxStatus.LISTENING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(PaperAccentBlue)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                audioRecording -> "Recording audio note…"
                                savingRemember -> "Listening for Remember…"
                                savingSpokenNote -> "Listening for your note…"
                                personal -> "Ask about your journal or library"
                                else -> "Listening…"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = PaperAccentBlue
                        )
                    }
                }
                VoxStatus.PAUSED -> {
                    Text(
                        text = "Paused",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = PaperKeySubText
                    )
                }
                VoxStatus.PREPARING -> {
                    Text("Preparing voice model…", fontSize = 12.sp, color = PaperKeySubText)
                }
                VoxStatus.PROCESSING -> {
                    Text(
                        text = "• Processing speech…",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = PaperAccentBlue
                    )
                }
                VoxStatus.PERMISSION_REQUIRED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Microphone permission needed",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MonologIncognitoShield,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onRequestPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = PaperAccentBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = "Grant", fontSize = 11.sp)
                        }
                    }
                }
                VoxStatus.ERROR, VoxStatus.UNAVAILABLE -> {
                    Text(
                        text = voxState.errorMessage ?: "Speech error. Tap play to retry.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    if (voxState.status == VoxStatus.UNAVAILABLE) {
                        TextButton(onOpenModels) { Text("Open Monolog settings") }
                    }
                }
                else -> {
                    Text(
                        text = "Tap play to start speaking",
                        fontSize = 12.sp,
                        color = PaperKeySubText
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            VoxTranscriptStage(
                transcript = fullTranscript,
                isListening = isListening,
                rmsLevel = voxState.rmsLevel
            )

            if (voxState.status == VoxStatus.PAUSED) {
                voxState.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            Box(Modifier.align(Alignment.CenterStart).widthIn(max = (maxWidth - 58.dp) / 2)) {
                Column(
                    Modifier.clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(enabled = !audioRecording && !savingSpokenNote, onClickLabel = "Change speech language") {
                            triggerHapticFeedback(view, enabled = vibration)
                            languagesOpen = true
                        }.padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text("Speech language", fontSize = 10.sp, maxLines = 1,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(language, Modifier.weight(1f, fill = false), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        MonologIcon(Glyph.DOWN, Modifier.padding(start = 3.dp).size(12.dp),
                            MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                DropdownMenu(languagesOpen, { languagesOpen = false }, Modifier.heightIn(max = 320.dp)) {
                    languages.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.title) },
                            trailingIcon = if (language == choice.title) ({ Text("✓") }) else null,
                            onClick = {
                                languagesOpen = false
                                if (language != choice.title) onLanguage(choice)
                            }
                        )
                    }
                    DropdownMenuItem(text = { Text("Voice engines & language packs") }, onClick = {
                        languagesOpen = false
                        onOpenModels()
                    })
                }
            }
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .size(52.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (isListening) PaperPillBackground else PaperAccentBlue)
                    .clickable(enabled = !preparing && voxState.status != VoxStatus.PROCESSING) {
                        triggerHapticFeedback(view, enabled = vibration)
                        if (isListening) onPauseListening() else onStartListening()
                    }
                    .semantics { contentDescription = playDescription },
                contentAlignment = Alignment.Center
            ) {
                if (preparing) CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                ) else Text(if (isListening) "❚❚" else "▶", fontSize = 18.sp,
                    color = if (isListening) PaperKeyText else MaterialTheme.colorScheme.onPrimary)
            }
            Row(Modifier.align(Alignment.CenterEnd).widthIn(max = (maxWidth - 58.dp) / 2),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (smartFilter) "Smart Filter on" else "Smart Filter off",
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(10.dp)).clickable {
                        triggerHapticFeedback(view, enabled = vibration)
                        onToggleSmartFilter()
                    }.padding(vertical = 10.dp, horizontal = 3.dp),
                    fontSize = 11.sp, color = PaperAccentBlue)
                if (!personal && !audioRecording && !savingSpokenNote) {
                    KeyButton(Modifier.size(36.dp), glyph = Glyph.BACKSPACE,
                        label = "Delete previous word", repeatIntervalMs = 220, onClick = onDeleteWord)
                }
            }
        }
    }
}


@Composable
private fun VoxTranscriptStage(
    transcript: String,
    isListening: Boolean,
    rmsLevel: Float,
    compact: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val surface = colors.surface
    val target by rememberUpdatedState(remember(transcript) {
        transcript.split(Regex("\\s+")).filter(String::isNotBlank)
    })
    var visible by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        snapshotFlow { target }.collectLatest { incoming ->
            var shared = 0
            while (shared < visible.size && shared < incoming.size && visible[shared] == incoming[shared]) shared++
            visible = incoming.take(shared)
            while (shared < incoming.size) {
                visible = incoming.take(++shared)
                if (shared < incoming.size) delay(if (incoming.size - shared > 6) 36 else 95)
            }
        }
    }
    val words = visible.takeLast(4)

    Box(
        Modifier.fillMaxWidth().height(if (compact) 46.dp else 142.dp)
            .glassSurface(18, GlassAccent.BLUE)
            .clip(RoundedCornerShape(18.dp))
    ) {
        AudioWaveformView(isListening, rmsLevel, Modifier.matchParentSize())

        Column(
            Modifier.align(Alignment.Center).fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(surface.copy(alpha = .14f), surface.copy(alpha = .78f), surface.copy(alpha = .14f))
                    )
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            if (transcript.isBlank()) {
                Text(
                    if (isListening) "Speak naturally…" else "Tap play to start",
                    modifier = Modifier.fillMaxWidth(),
                    color = PaperKeySubText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            } else {
                Box(Modifier.fillMaxWidth().height(if (compact) 30.dp else 67.dp).semantics { contentDescription = transcript }) {
                    Row(
                        Modifier.align(Alignment.CenterStart).fillMaxWidth(.28f).clipToBounds(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        words.dropLast(1).forEachIndexed { index, word ->
                            val distance = words.lastIndex - index
                            Text(
                                word,
                                modifier = Modifier.weight(1f, fill = false).padding(end = 3.dp),
                                fontSize = (15 - distance).sp,
                                color = PaperKeySubText.copy(alpha = 1f - distance * .15f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    AnimatedContent(
                        targetState = visible.size to words.lastOrNull().orEmpty(),
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(.44f),
                        contentAlignment = Alignment.Center,
                        transitionSpec = {
                            (slideInHorizontally(tween(105)) { it / 4 } + fadeIn(tween(105))) togetherWith
                                (slideOutHorizontally(tween(105)) { -it / 2 } + fadeOut(tween(105)))
                        },
                        label = "Live transcript"
                    ) { (_, word) ->
                        Text(
                            word,
                            color = PaperKeyText,
                            fontSize = if (compact) 15.sp else 32.sp,
                            lineHeight = if (compact) 17.sp else 34.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun AudioWaveformView(isListening: Boolean, rmsLevel: Float, modifier: Modifier = Modifier) {
    val level = animateFloatAsState(
        targetValue = if (isListening) rmsLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(80),
        label = "Voice level"
    )
    val phase = if (isListening) {
        rememberInfiniteTransition(label = "Waveform flow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "Right-to-left phase"
        )
    } else null
    val centerColor = PaperAccentBlue.copy(alpha = .68f)
    val edgeColor = MaterialTheme.colorScheme.secondary.copy(alpha = .44f)
    val idleColor = MaterialTheme.colorScheme.outline.copy(alpha = .43f)
    val barCount = 28
    val center = (barCount - 1) / 2f

    Row(
        modifier = modifier.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val distance = kotlin.math.abs(index - center) / center
            val taper = 1f - distance * .78f
            val color = when {
                !isListening -> idleColor
                distance < .4f -> centerColor
                else -> edgeColor
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(3.dp).height(122.dp)
                        .graphicsLayer {
                            val travel = phase?.value
                            scaleY = if (travel == null) {
                                .035f
                            } else {
                                val crest = kotlin.math.sin((travel + index / 14f) * 2.0 * kotlin.math.PI).toFloat()
                                (.055f + level.value * .94f) * taper * (.84f + crest * .16f)
                            }
                        }
                        .background(color, CircleShape)
                )
            }
        }
    }
}
