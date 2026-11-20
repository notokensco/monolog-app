package com.notokens.monolog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PaperKeyText: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val PaperKeySubText: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val PaperKeyActive: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer

val PaperPillBackground: Color @Composable get() = when {
    LocalReduceTransparency.current -> MaterialTheme.colorScheme.surfaceContainerHigh
    LocalMonologDark.current -> Color(0x26C4CDFF)
    else -> Color(0xA8FFFFFF)
}
val PaperPillBorder: Color @Composable get() = when {
    LocalReduceTransparency.current -> MaterialTheme.colorScheme.outlineVariant
    LocalMonologDark.current -> Color(0x35D7DDFF)
    else -> Color(0xCCFFFFFF)
}

val PaperAccentBlue: Color @Composable get() = MaterialTheme.colorScheme.primary
val PaperAccentLavender: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
val PaperAccentLavenderBorder: Color @Composable get() = MaterialTheme.colorScheme.secondary.copy(alpha = .65f)

val MonologIncognitoShield: Color @Composable get() =
    if (LocalMonologDark.current) Color(0xFFFFC980) else Color(0xFF8F4E14)
val MonologProtectedBg: Color @Composable get() = PaperPillBackground
val MonologProtectedBorder: Color @Composable get() = PaperPillBorder
