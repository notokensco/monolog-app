package com.notokens.monolog.ime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.R
import com.notokens.monolog.ime.EditorContext
import com.notokens.monolog.ui.theme.MonologIncognitoShield
import com.notokens.monolog.ui.theme.MonologProtectedBg
import com.notokens.monolog.ui.theme.MonologProtectedBorder
import com.notokens.monolog.ui.theme.PaperAccentLavender
import com.notokens.monolog.ui.theme.PaperAccentLavenderBorder
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import com.notokens.monolog.ui.theme.PaperPillBackground
import com.notokens.monolog.ui.theme.PaperPillBorder

@Composable
fun KeyboardHeader(
    editorContext: EditorContext,
    isShortcutsOpen: Boolean,
    onToggleShortcuts: () -> Unit,
    onEnterVox: () -> Unit,
    onCloseKeyboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Dismiss keyboard caret ⌄
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable { onCloseKeyboard() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⌄",
                color = PaperKeyText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Center: Suggestion / Action Pill or Protected Shield
        if (editorContext.isIncognito) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MonologProtectedBg)
                    .border(1.dp, MonologProtectedBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(text = "🛡️", fontSize = 12.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Protected • Sensitive field (Mic/M disabled)",
                    color = MonologIncognitoShield,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            // Action chip matching reference: "⏰ Set 5 PM exam reminder" or Quick prompt
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .shadow(1.dp, RoundedCornerShape(18.dp))
                    .background(PaperPillBackground)
                    .border(1.dp, PaperPillBorder, RoundedCornerShape(18.dp))
                    .clickable { onToggleShortcuts() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "⏰",
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Set 5 PM exam reminder",
                    color = PaperKeyText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Right Controls: Mic (VoxKey) & M Key Stack
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Mic Control
            val micAlpha = if (editorContext.isIncognito) 0.35f else 1f
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(PaperAccentLavender.copy(alpha = micAlpha))
                    .border(1.dp, PaperAccentLavenderBorder.copy(alpha = micAlpha), CircleShape)
                    .clickable(enabled = !editorContext.isIncognito) { onEnterVox() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (editorContext.isIncognito) "🔒" else "🎙",
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // M Key (using screen_design/monolog.png drawable)
            val mAlpha = if (editorContext.isIncognito) 0.35f else 1f
            val mBorder = if (isShortcutsOpen) {
                Modifier.border(2.dp, Color(0xFF4A70F5), RoundedCornerShape(8.dp))
            } else {
                Modifier
            }

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .shadow(if (isShortcutsOpen) 4.dp else 2.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .then(mBorder)
                    .clickable(enabled = !editorContext.isIncognito) { onToggleShortcuts() },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_monolog_m),
                    contentDescription = "Monolog M Key",
                    alpha = mAlpha,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}
