package com.notokens.monolog.ime.vox

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    onCommitTranscript: (String) -> Unit,
    onCancel: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        // Top Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconControl(Glyph.BACK, "Return to keyboard", onCancel, frosted = true)
                Text(
                    text = "Vox mode",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PaperKeyText
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Active glowing mic pill
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(PaperAccentLavender)
                        .border(1.dp, PaperAccentLavenderBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    MonologIcon(Glyph.MIC, Modifier.size(16.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                }

                Spacer(modifier = Modifier.width(8.dp))

                MonologLogo(Modifier.size(40.dp))
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
                    text = "• Paused (Tap mic to continue)",
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
            VoxStatus.ERROR -> {
                Text(
                    text = voxState.errorMessage ?: "Speech error. Tap mic to retry.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            else -> {
                Text(
                    text = "Tap microphone below to start speaking",
                    fontSize = 12.sp,
                    color = PaperKeySubText
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Real-time audio waveform driven by actual rmsLevel
        AudioWaveformView(
            isListening = voxState.status == VoxStatus.LISTENING,
            rmsLevel = voxState.rmsLevel
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Live Transcript Review Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(16, GlassAccent.BLUE)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (voxState.transcript.isNotBlank()) {
                        voxState.transcript
                    } else {
                        "Speak naturally. Words appear here for review before inserting."
                    },
                    fontSize = 13.sp,
                    color = if (voxState.transcript.isNotBlank()) PaperKeyText else PaperKeySubText,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f)
                )

                if (voxState.transcript.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clear",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PaperAccentBlue,
                        modifier = Modifier
                            .clickable { onClearTranscript() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .glassSurface(16)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = voxState.language,
                    fontSize = 12.sp,
                    color = PaperKeyText
                )
            }

            // Central Mic / Pause Action Button
            val isListening = voxState.status == VoxStatus.LISTENING
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (isListening) PaperPillBackground else PaperAccentBlue)
                    .border(
                        1.dp,
                        if (isListening) PaperAccentLavenderBorder else Color.Transparent,
                        CircleShape
                    )
                    .clickable {
                        if (isListening) onPauseListening() else onStartListening()
                    }
                    .semantics { contentDescription = if (isListening) "Pause listening" else "Start listening" },
                contentAlignment = Alignment.Center
            ) {
                if (isListening) {
                    Text(text = "❚❚", fontSize = 18.sp, color = PaperKeyText)
                } else {
                    MonologIcon(Glyph.MIC, color = MaterialTheme.colorScheme.onPrimary)
                }
            }

            Button(
                enabled = voxState.transcript.isNotBlank(),
                onClick = { onCommitTranscript(voxState.transcript) }
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun AudioWaveformView(
    isListening: Boolean,
    rmsLevel: Float,
    modifier: Modifier = Modifier
) {
    // 28 waveform bars dynamically modulated by real RMS volume
    val barCount = 28
    val centerIndex = barCount / 2

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val distFromCenter = Math.abs(i - centerIndex).toFloat() / centerIndex
            val bellFactor = (1f - distFromCenter * 0.7f).coerceIn(0.2f, 1f)

            val targetHeight = if (isListening) {
                val variableHeight = 4f + (rmsLevel * 28f * bellFactor)
                variableHeight.dp
            } else {
                3.dp
            }

            val animatedHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = tween(durationMillis = 80),
                label = "barHeight$i"
            )

            val barColor = if (!isListening) MaterialTheme.colorScheme.outline
                else if (distFromCenter < 0.4f) PaperAccentBlue
                else MaterialTheme.colorScheme.secondary

            Box(
                modifier = Modifier
                    .padding(horizontal = 1.5.dp)
                    .width(3.dp)
                    .height(animatedHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}
