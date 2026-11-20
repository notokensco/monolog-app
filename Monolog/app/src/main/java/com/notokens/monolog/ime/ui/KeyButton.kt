package com.notokens.monolog.ime.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
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

@Composable
fun KeyButton(
    modifier: Modifier = Modifier,
    text: String? = null,
    subText: String? = null,
    fontSize: TextUnit = 20.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    isSpecial: Boolean = false,
    isAccent: Boolean = false,
    isActive: Boolean = false,
    isPill: Boolean = false,
    contentColor: Color? = null,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

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
        isAccent -> Color.White
        isActive -> PaperAccentBlue
        isSpecial -> PaperKeyText.copy(alpha = 0.85f)
        else -> PaperKeyText
    }

    val pillModifier = if (isPill) {
        Modifier
            .shadow(1.dp, RoundedCornerShape(20.dp))
            .border(1.dp, PaperPillBorder, RoundedCornerShape(20.dp))
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 2.5.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(pillModifier)
            .background(finalBg)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (content != null) {
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
