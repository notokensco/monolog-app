package com.notokens.monolog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.data.ThemeMode

val LocalMonologDark = staticCompositionLocalOf { true }
val LocalReduceTransparency = staticCompositionLocalOf { false }

private val InkColors = darkColorScheme(
    primary = Color(0xFFC3C9FF),
    onPrimary = Color(0xFF24284F),
    primaryContainer = Color(0xFF34395D),
    onPrimaryContainer = Color(0xFFE1E4FF),
    secondary = Color(0xFFD8B9ED),
    secondaryContainer = Color(0xFF403052),
    onSecondaryContainer = Color(0xFFF1D9FF),
    background = Color(0xFF08090B),
    surface = Color(0xFF16171C),
    surfaceContainer = Color(0xFF1E202A),
    surfaceContainerHigh = Color(0xFF262936),
    onBackground = Color(0xFFF7F8FC),
    onSurface = Color(0xFFF7F8FC),
    surfaceVariant = Color(0xFF22232A),
    onSurfaceVariant = Color(0xFFB9BFCC),
    outline = Color(0xFF7E8398),
    outlineVariant = Color(0xFF393C46)
)

private val PaperColors = lightColorScheme(
    primary = Color(0xFF404F91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF242E5B),
    secondary = Color(0xFF705584),
    secondaryContainer = Color(0xFFF0E0F7),
    onSecondaryContainer = Color(0xFF3D294E),
    background = Color(0xFFFAFAFC),
    surface = Color.White,
    surfaceContainer = Color(0xFFF1F2F9),
    surfaceContainerHigh = Color(0xFFE9EBF5),
    onBackground = Color(0xFF151823),
    onSurface = Color(0xFF151823),
    surfaceVariant = Color(0xFFF1F2F6),
    onSurfaceVariant = Color(0xFF535B6B),
    outline = Color(0xFF777E93),
    outlineVariant = Color(0xFFD9DDE6)
)

@Composable
fun MonologTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val darkTheme = when (appearance.theme) {
        ThemeMode.PAPER -> false
        ThemeMode.INK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(
        LocalMonologDark provides darkTheme,
        LocalReduceTransparency provides appearance.reduceTransparency
    ) {
        MaterialTheme(colorScheme = if (darkTheme) InkColors else PaperColors, typography = Typography, content = content)
    }
}
