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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.notokens.monolog.R
import com.notokens.monolog.ime.ui.ContextKey
import com.notokens.monolog.ime.ui.triggerHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.TextButton
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
    onStartListening: () -> Unit,
    onPauseListening: () -> Unit,
    onClearTranscript: () -> Unit,
    smartFilter: Boolean,
    onToggleSmartFilter: () -> Unit,
    contextEnabled: Boolean,
    contextActive: Boolean,
    onContext: () -> Unit,
    onContextHold: () -> Unit,
    onCancel: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenModels: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val fullTranscript = listOf(voxState.transcript, voxState.partialTranscript)
        .filter(String::isNotBlank).joinToString(" ")
    val isListening = voxState.status == VoxStatus.LISTENING
    val view = LocalView.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .glassSurface(24, GlassAccent.LAVENDER)
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * .47f).dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) {
                IconControl(Glyph.BACK, "Return to keyboard", onCancel, frosted = true)
            }
            Text("VOX", Modifier.align(Alignment.Center), color = PaperKeyText, fontSize = 22.sp,
                fontFamily = FontFamily(Font(R.font.science_gothic_vox, FontWeight.SemiBold)),
                fontWeight = FontWeight.SemiBold)
            Box(Modifier.align(Alignment.CenterEnd)) {
                ContextKey(contextEnabled, contextActive, onContext, onContextHold)
            }
        }

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
                        text = "Listening…",
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
            rmsLevel = voxState.rmsLevel,
            onClear = onClearTranscript
        )

        if (voxState.status == VoxStatus.PAUSED) {
            voxState.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(Modifier.fillMaxWidth().height(56.dp)) {
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .size(52.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (isListening) PaperPillBackground else PaperAccentBlue)
                    .clickable(enabled = voxState.status != VoxStatus.PROCESSING) {
                        triggerHapticFeedback(view)
                        if (isListening) onPauseListening() else onStartListening()
                    }
                    .semantics { contentDescription = if (isListening) "Pause listening" else "Start listening" },
                contentAlignment = Alignment.Center
            ) {
                Text(if (isListening) "❚❚" else "▶", fontSize = 18.sp,
                    color = if (isListening) PaperKeyText else MaterialTheme.colorScheme.onPrimary)
            }
            TextButton(modifier = Modifier.align(Alignment.CenterEnd), onClick = {
                triggerHapticFeedback(view)
                onToggleSmartFilter()
            }) { Text(if (smartFilter) "Smart Filter on" else "Smart Filter off", fontSize = 11.sp) }
        }
    }
}


@Composable
private fun VoxTranscriptStage(
    transcript: String,
    isListening: Boolean,
    rmsLevel: Float,
    onClear: () -> Unit
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
        Modifier.fillMaxWidth().height(142.dp)
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
                Box(Modifier.fillMaxWidth().height(67.dp).semantics { contentDescription = transcript }) {
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
                            fontSize = 32.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!isListening) {
                    Text(
                        transcript, Modifier.fillMaxWidth().heightIn(max = 38.dp)
                            .verticalScroll(rememberScrollState()),
                        fontSize = 12.sp, color = PaperKeySubText
                    )
                }
            }
        }

        if (transcript.isNotBlank()) {
            Text(
                "Clear", Modifier.align(Alignment.TopEnd).clickable(onClick = onClear).padding(8.dp),
                fontSize = 12.sp, color = PaperAccentBlue
            )
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
