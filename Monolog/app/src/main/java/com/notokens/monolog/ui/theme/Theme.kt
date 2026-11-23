package com.notokens.monolog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.notokens.monolog.data.Appearance
import com.notokens.monolog.data.ThemeMode

val LocalMonologDark = staticCompositionLocalOf { true }
val LocalReduceTransparency = staticCompositionLocalOf { false }
internal val LocalThemeMode = staticCompositionLocalOf { ThemeMode.INK }
internal val LocalThemePhoto = staticCompositionLocalOf<ImageBitmap?> { null }

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
        ThemeMode.PAPER, ThemeMode.IRIS, ThemeMode.OCEAN, ThemeMode.TERRACOTTA, ThemeMode.PHOTO -> false
        ThemeMode.INK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current.applicationContext
    val photo by produceState<ImageBitmap?>(null, appearance.theme, appearance.wallpaperRevision, appearance.reduceTransparency) {
        value = if (appearance.theme != ThemeMode.PHOTO || appearance.reduceTransparency) null else
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeFile(ThemeWallpaper.file(context).path)?.asImageBitmap()
            }
    }
    val colors = when (appearance.theme) {
        ThemeMode.IRIS -> PaperColors.copy(background = Color(0xFFE8EAFB), primary = Color(0xFF404F91))
        ThemeMode.OCEAN -> PaperColors.copy(background = Color(0xFFE1F4F3), primary = Color(0xFF185F73),
            primaryContainer = Color(0xFFBDE5E7), onPrimaryContainer = Color(0xFF124A56),
            secondary = Color(0xFF326B53), surfaceContainer = Color(0xFFE8F5F2))
        ThemeMode.TERRACOTTA -> PaperColors.copy(background = Color(0xFFF9E9DC), primary = Color(0xFF863E35),
            primaryContainer = Color(0xFFFAD7C7), onPrimaryContainer = Color(0xFF613127),
            secondary = Color(0xFF774873), surfaceContainer = Color(0xFFF7ECE7))
        ThemeMode.PHOTO -> PaperColors
        else -> if (darkTheme) InkColors.copy(primary = Color(0xFFD6D8DF), primaryContainer = Color(0xFF34363D))
            else PaperColors.copy(primary = Color(0xFF444951), primaryContainer = Color(0xFFE5E5E8))
    }
    CompositionLocalProvider(
        LocalMonologDark provides darkTheme,
        LocalReduceTransparency provides appearance.reduceTransparency,
        LocalThemeMode provides appearance.theme,
        LocalThemePhoto provides photo.takeIf { appearance.theme == ThemeMode.PHOTO }
    ) {
        MaterialTheme(colorScheme = colors, typography = Typography) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
        }
    }
}
