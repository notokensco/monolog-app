package com.notokens.monolog.ime.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsClick
import androidx.compose.ui.semantics.onLongClick as semanticsLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.ui.theme.PaperAccentBlue
import com.notokens.monolog.ui.theme.PaperKeyActive
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import com.notokens.monolog.ui.theme.PaperPillBackground
import com.notokens.monolog.ui.theme.PaperPillBorder
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.MonologIcon

internal fun triggerHapticFeedback(view: View, isLong: Boolean = false) {
    view.performHapticFeedback(if (isLong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
}

@Composable
fun KeyButton(
    modifier: Modifier = Modifier,
    text: String? = null,
    subText: String? = null,
    fontSize: TextUnit = 23.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    isSpecial: Boolean = false,
    isAccent: Boolean = false,
    isActive: Boolean = false,
    isPill: Boolean = false,
    contentColor: Color? = null,
    glyph: Glyph? = null,
    label: String? = null,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null
) {
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onLongClick)

    val baseBg = when {
        isAccent -> PaperAccentBlue
        isActive -> PaperKeyActive
        isPill -> PaperPillBackground
        isPressed -> PaperKeyActive.copy(alpha = 0.8f)
        else -> Color.Transparent
    }

    val finalBg = if (isPressed && !isAccent) {
        PaperKeyActive
    } else {
        baseBg
    }

    val textColor = contentColor ?: when {
        isAccent -> MaterialTheme.colorScheme.onPrimary
        isActive -> PaperAccentBlue
        isSpecial -> PaperKeyText.copy(alpha = 0.85f)
        else -> PaperKeyText
    }

    val pillModifier = if (isPill) {
        Modifier.border(if (isActive) 2.dp else 1.dp,
            if (isActive) MaterialTheme.colorScheme.primary else PaperPillBorder, RoundedCornerShape(24.dp))
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = if (isPill) 7.dp else 2.dp)
            .clip(RoundedCornerShape(if (isPill) 24.dp else 8.dp))
            .then(pillModifier)
            .background(finalBg)
            .semantics {
                val description = label ?: glyph?.name?.lowercase()
                if (description != null) contentDescription = description
                semanticsClick { onClick(); true }
                if (onLongClick != null) semanticsLongClick(longClickLabel) { onLongClick(); true }
            }
            .pointerInput(onLongClick != null) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try { tryAwaitRelease() } finally { isPressed = false }
                    },
                    onLongPress = {
                        triggerHapticFeedback(view, isLong = true)
                        (hold ?: tap)()
                    },
                    onTap = {
                        triggerHapticFeedback(view)
                        tap()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (glyph != null) {
            MonologIcon(glyph, Modifier.size(23.dp), textColor)
        } else if (content != null) {
            content()
        } else if (text != null) {
            if (subText != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 1.dp)
                ) {
                    Text(
                        text = subText,
                        color = PaperKeySubText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 10.sp
                    )
                    Text(
                        text = text,
                        color = textColor,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text(
                    text = text,
                    color = textColor,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
