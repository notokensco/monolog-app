package com.notokens.monolog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.notokens.monolog.R
import com.notokens.monolog.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

enum class Glyph { INFO, DASHBOARD, CLIPBOARD, DOWN, CLOSE, EXPAND, BOLT, CLOCK, BELL, ALARM, BOOK, LAYERS, PLUS, HOME, SETTINGS, MIC, GLOBE, SMILE, SHIFT, BACKSPACE, ENTER, SEARCH, SHIELD, PRIVACY, ARROW, BACK, DOT, PAPER, INK, PHOTO, CAPTURE, EDIT, REWRITE, DRAG_HANDLE, PIN, SHARE, CHEVRON, DOUBLE_DOWN, REFRESH, GRID, LIST, SORT_DOWN, SORT_UP, SORT_TITLE_DOWN, SORT_TITLE_UP }

@Composable
fun MonologIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = PaperKeyText) {
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val path = Path()
            fun line(vararg points: Float) {
                path.moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) path.lineTo(points[i], points[i + 1])
            }
            fun circle(x: Float, y: Float, radius: Float) { drawCircle(color, radius, Offset(x, y), style = Stroke(1.7f)) }
            when (glyph) {
                Glyph.INFO -> {
                    circle(12f, 12f, 9f)
                    drawCircle(color, 1f, Offset(12f, 7.5f))
                    line(12f, 10.5f, 12f, 16.5f)
                }
                Glyph.SHARE -> {
                    line(12f, 15f, 12f, 3f); line(8f, 7f, 12f, 3f, 16f, 7f)
                    line(6f, 11f, 4f, 11f, 4f, 21f, 20f, 21f, 20f, 11f, 18f, 11f)
                }
                Glyph.CHEVRON -> line(9f, 5f, 16f, 12f, 9f, 19f)
                Glyph.DOUBLE_DOWN -> { line(6f, 5f, 12f, 11f, 18f, 5f); line(6f, 12f, 12f, 18f, 18f, 12f) }
                Glyph.DASHBOARD -> {
                    drawCircle(color, 1.7f, Offset(5f, 12f))
                    drawCircle(color, 1.7f, Offset(12f, 12f))
                    drawCircle(color, 1.7f, Offset(19f, 12f))
                }
                Glyph.CLIPBOARD -> {
                    line(8f, 5f, 5f, 5f, 5f, 22f, 19f, 22f, 19f, 5f, 16f, 5f)
                    line(8f, 3f, 16f, 3f, 16f, 8f, 8f, 8f, 8f, 3f)
                    line(9f, 13f, 15f, 13f)
                    line(9f, 17f, 15f, 17f)
                }
                Glyph.DOWN -> line(5f, 9f, 12f, 16f, 19f, 9f)
                Glyph.REFRESH -> {
                    path.arcTo(Rect(4f, 4f, 20f, 20f), 15f, 300f, forceMoveTo = true)
                    line(18f, 2.5f, 18f, 7f, 13.5f, 7f)
                }
                Glyph.GRID -> {
                    for (x in listOf(4f, 14f)) for (y in listOf(4f, 14f)) {
                        path.addRoundRect(RoundRect(Rect(x, y, x + 6f, y + 6f), CornerRadius(1f)))
                    }
                }
                Glyph.LIST -> for (y in listOf(6f, 12f, 18f)) {
                    line(4f, y, 5f, y)
                    line(9f, y, 20f, y)
                }
                Glyph.SORT_DOWN, Glyph.SORT_UP, Glyph.SORT_TITLE_DOWN, Glyph.SORT_TITLE_UP -> {
                    if (glyph == Glyph.SORT_TITLE_DOWN || glyph == Glyph.SORT_TITLE_UP) {
                        line(3f, 16f, 7f, 6f, 11f, 16f)
                        line(4.5f, 12f, 9.5f, 12f)
                    } else {
                        line(3f, 6f, 12f, 6f)
                        line(3f, 12f, 10f, 12f)
                        line(3f, 18f, 8f, 18f)
                    }
                    line(18f, 5f, 18f, 19f)
                    if (glyph == Glyph.SORT_DOWN || glyph == Glyph.SORT_TITLE_DOWN) line(14f, 15f, 18f, 19f, 22f, 15f)
                    else line(14f, 9f, 18f, 5f, 22f, 9f)
                }
                Glyph.CLOSE -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
                Glyph.EXPAND -> { line(14f, 4f, 20f, 4f, 20f, 10f); line(20f, 4f, 14f, 10f); line(10f, 20f, 4f, 20f, 4f, 14f); line(4f, 20f, 10f, 14f) }
                Glyph.BOLT -> line(13f, 2f, 5f, 13f, 11f, 13f, 9f, 22f, 20f, 9f, 13f, 9f, 13f, 2f)
                Glyph.CLOCK, Glyph.ALARM -> { circle(12f, 13f, 8f); line(12f, 8f, 12f, 13f, 16f, 15f); if (glyph == Glyph.ALARM) { line(3f, 6f, 7f, 2f); line(17f, 2f, 21f, 6f); line(6f, 20f, 4f, 23f); line(18f, 20f, 20f, 23f) } }
                Glyph.BELL -> { path.moveTo(5f, 10f); path.cubicTo(5f, 2f, 19f, 2f, 19f, 10f); line(5f, 10f, 5f, 15f, 3f, 18f, 21f, 18f, 19f, 15f, 19f, 10f); path.moveTo(9f, 21f); path.quadraticTo(12f, 24f, 15f, 21f) }
                Glyph.BOOK -> { line(12f, 5f, 12f, 21f); path.moveTo(12f, 5f); path.quadraticTo(7f, 1f, 2f, 4f); line(2f, 4f, 2f, 20f); path.quadraticTo(7f, 17f, 12f, 21f); path.quadraticTo(17f, 17f, 22f, 20f); line(22f, 20f, 22f, 4f); path.quadraticTo(17f, 1f, 12f, 5f) }
                Glyph.LAYERS -> { line(2f, 7f, 12f, 2f, 22f, 7f, 12f, 12f, 2f, 7f); line(2f, 12f, 12f, 17f, 22f, 12f); line(2f, 17f, 12f, 22f, 22f, 17f) }
                Glyph.PLUS -> { line(12f, 3f, 12f, 21f); line(3f, 12f, 21f, 12f) }
                Glyph.HOME -> line(3f, 10f, 12f, 2f, 21f, 10f, 21f, 22f, 15f, 22f, 15f, 15f, 9f, 15f, 9f, 22f, 3f, 22f, 3f, 10f)
                Glyph.SETTINGS -> {
                    circle(12f, 12f, 3.15f)
                    val tooth = listOf(-.39 to 7.7, -.21 to 8.1, -.16 to 10.05, .16 to 10.05, .21 to 8.1, .39 to 7.7)
                    for (step in 0 until 8) {
                        tooth.forEachIndexed { index, (offset, radius) ->
                            val angle = (step - 2) * Math.PI / 4 + offset
                            val x = (12 + cos(angle) * radius).toFloat()
                            val y = (12 + sin(angle) * radius).toFloat()
                            if (step == 0 && index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                    }
                    path.close()
                }
                Glyph.MIC -> { path.moveTo(8f, 6f); path.cubicTo(8f, 0f, 16f, 0f, 16f, 6f); line(16f, 6f, 16f, 12f); path.cubicTo(16f, 18f, 8f, 18f, 8f, 12f); line(8f, 12f, 8f, 6f); path.moveTo(4f, 11f); path.cubicTo(4f, 22f, 20f, 22f, 20f, 11f); line(12f, 20f, 12f, 24f) }
                Glyph.GLOBE -> { circle(12f, 12f, 10f); line(2f, 12f, 22f, 12f); path.moveTo(12f, 2f); path.cubicTo(3f, 9f, 3f, 15f, 12f, 22f); path.cubicTo(21f, 15f, 21f, 9f, 12f, 2f) }
                Glyph.SMILE -> { circle(12f, 12f, 10f); drawCircle(color, 1.2f, Offset(8f, 9.5f)); drawCircle(color, 1.2f, Offset(16f, 9.5f)); path.moveTo(7.5f, 14f); path.quadraticTo(12f, 20f, 16.5f, 14f) }
                Glyph.SHIFT -> { line(4f, 11f, 12f, 3f, 20f, 11f); line(12f, 3f, 12f, 22f) }
                Glyph.BACKSPACE -> { line(9f, 4f, 22f, 4f, 22f, 20f, 9f, 20f, 2f, 12f, 9f, 4f); line(12f, 9f, 18f, 15f); line(18f, 9f, 12f, 15f) }
                Glyph.ENTER -> { line(21f, 5f, 21f, 14f, 3f, 14f); line(8f, 9f, 3f, 14f, 8f, 19f) }
                Glyph.SEARCH -> { circle(10f, 10f, 7f); line(15f, 15f, 22f, 22f) }
                Glyph.SHIELD -> { line(3f, 4f, 12f, 1f, 21f, 4f, 21f, 12f); path.quadraticTo(21f, 18f, 12f, 23f); path.quadraticTo(3f, 18f, 3f, 12f); line(3f, 12f, 3f, 4f) }
                Glyph.PRIVACY -> {
                    // Detective Fedora hat and sunglasses matching design
                    // Hat brim
                    line(2f, 9f, 22f, 9f)
                    // Hat crown
                    path.moveTo(6f, 9f)
                    path.lineTo(7f, 3f)
                    path.lineTo(12f, 5f)
                    path.lineTo(17f, 3f)
                    path.lineTo(18f, 9f)
                    // Sunglasses
                    circle(7f, 14.5f, 3f)
                    circle(17f, 14.5f, 3f)
                    line(10f, 14f, 14f, 14f)
                }
                Glyph.ARROW -> { line(3f, 12f, 21f, 12f); line(15f, 6f, 21f, 12f, 15f, 18f) }
                Glyph.BACK -> { line(21f, 12f, 3f, 12f); line(9f, 6f, 3f, 12f, 9f, 18f) }
                Glyph.DOT -> { drawCircle(color, 2.5f, Offset(12f, 12f)) }
                Glyph.EDIT -> {
                    line(4f, 16f, 16f, 4f, 20f, 8f, 8f, 20f, 3f, 21f, 4f, 16f)
                    line(14f, 6f, 18f, 10f)
                }
                Glyph.REWRITE -> {
                    line(3f, 16f, 13f, 6f, 17f, 10f, 7f, 20f, 2f, 21f, 3f, 16f)
                    line(11f, 8f, 15f, 12f)
                    line(19f, 1f, 20f, 4f, 23f, 5f, 20f, 6f, 19f, 9f, 18f, 6f, 15f, 5f, 18f, 4f, 19f, 1f)
                }
                Glyph.DRAG_HANDLE -> {
                    for (y in listOf(5f, 12f, 19f)) {
                        drawCircle(color, 1.6f, Offset(8f, y))
                        drawCircle(color, 1.6f, Offset(16f, y))
                    }
                }
                Glyph.PHOTO -> {
                    line(3f, 3f, 21f, 3f, 21f, 21f, 3f, 21f, 3f, 3f)
                    circle(8f, 8f, 1.5f)
                    line(3f, 17f, 9f, 12f, 13f, 16f, 17f, 12f, 21f, 16f)
                }
                Glyph.CAPTURE -> {
                    line(3f, 8f, 3f, 3f, 8f, 3f)
                    line(16f, 3f, 21f, 3f, 21f, 8f)
                    line(21f, 16f, 21f, 21f, 16f, 21f)
                    line(8f, 21f, 3f, 21f, 3f, 16f)
                    circle(12f, 12f, 3f)
                }
                Glyph.PIN -> {
                    circle(12f, 7f, 3.5f)
                    line(12f, 10.5f, 12f, 21f)
                }
                Glyph.PAPER -> {
                    line(5f, 2f, 14f, 2f, 20f, 8f, 20f, 22f, 5f, 22f, 5f, 2f)
                    line(14f, 2f, 14f, 8f, 20f, 8f)
                    line(8f, 12f, 17f, 12f)
                    line(8f, 16f, 15f, 16f)
                }
                Glyph.INK -> {
                    path.moveTo(12f, 2f)
                    path.cubicTo(15f, 7f, 19f, 11f, 19f, 15f)
                    path.cubicTo(19f, 24f, 5f, 24f, 5f, 15f)
                    path.cubicTo(5f, 11f, 9f, 7f, 12f, 2f)
                    path.close()
                }
            }
            drawPath(path, color, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun IconControl(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    frosted: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true
) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(if (compact) 30.dp else 36.dp)
                .then(if (frosted) Modifier.glassSurface(if (compact) 15 else 18) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            MonologIcon(glyph, Modifier.size(if (compact) 17.dp else 20.dp),
                color = if (enabled) PaperKeyText else PaperKeySubText.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun MonologLogo(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Image(painterResource(R.drawable.ic_monolog_m), contentDescription = null, modifier, alpha = alpha)
}

@Composable
fun MonologMark(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Image(
        painterResource(R.drawable.ic_monolog_monochrome), contentDescription = null,
        modifier = modifier, alpha = alpha, colorFilter = ColorFilter.tint(PaperKeyText)
    )
}

@Composable
fun Modifier.monologBackdrop(keyboard: Boolean = false): Modifier {
    val dark = LocalMonologDark.current
    val background = MaterialTheme.colorScheme.background
    if (LocalReduceTransparency.current) return background(background)
    val theme = com.notokens.monolog.ui.theme.LocalThemeMode.current
    val photo = com.notokens.monolog.ui.theme.LocalThemePhoto.current
    val neutral = theme == com.notokens.monolog.data.ThemeMode.INK || theme == com.notokens.monolog.data.ThemeMode.PAPER
    val palette = when (theme) {
        com.notokens.monolog.data.ThemeMode.OCEAN -> listOf(Color(0xFFE7F6F5), Color(0xFFB7E2E8), Color(0xFFD4EADA))
        com.notokens.monolog.data.ThemeMode.TERRACOTTA -> listOf(Color(0xFFFFF1E5), Color(0xFFF3CFC5), Color(0xFFEFDAE9))
        else -> if (neutral) {
            if (dark) listOf(background, Color(0xFF18191D), Color(0xFF25262C))
            else listOf(Color(0xFFFAF9F7), Color(0xFFEEEDEA), Color(0xFFF8F7F4))
        } else if (dark) listOf(background, Color(0xFF131722), Color(0xFF191928))
        else listOf(Color(0xFFF1F2FB), Color(0xFFDDE2FB), Color(0xFFF1E8F4))
    }

    return drawWithCache {
        val base = Brush.verticalGradient(palette)
        fun glow(color: Color, x: Float, y: Float, radius: Float) = Brush.radialGradient(
            listOf(color, color.copy(alpha = .12f), color.copy(alpha = 0f)),
            center = Offset(size.width * x, size.height * y),
            radius = (size.maxDimension * radius).coerceAtLeast(1f)
        )
        val blue = glow(if (dark) Color(0xA16F9CCC) else Color(0xB9A6B9EF), -.08f, if (keyboard) .77f else .38f, .57f)
        val lavender = glow(if (dark) Color(0x8C937DBB) else Color(0xAAC0B3EA), 1.05f, if (keyboard) .56f else .21f, .54f)
        val blush = glow(if (dark) Color(0x66925A7C) else Color(0xC2FFD9D3), 1.02f, if (keyboard) .77f else .66f, .34f)
        val light = glow(if (dark) Color(0x285C73B2) else Color(0xCCFFFFFF), .38f, .08f, .64f)

        val splash = Path().apply {
            moveTo(6f, 24f)
            cubicTo(10f, 14f, 27f, 16f, 34f, 11f)
            cubicTo(39f, 8f, 46f, -1f, 50f, 1f)
            cubicTo(53f, 3f, 46f, 13f, 52f, 14f)
            cubicTo(61f, 16f, 73f, 10f, 76f, 13f)
            cubicTo(79f, 16f, 57f, 22f, 61f, 26f)
            cubicTo(65f, 31f, 85f, 34f, 82f, 39f)
            cubicTo(79f, 44f, 62f, 34f, 54f, 35f)
            cubicTo(44f, 37f, 55f, 49f, 48f, 52f)
            cubicTo(43f, 54f, 40f, 45f, 34f, 44f)
            cubicTo(18f, 42f, 3f, 39f, 6f, 24f)
            close()
        }
        val splashScale = minOf(size.width / 215f, size.height / 175f, 1.8.dp.toPx())
        val inkWash = Color(0x90212A54)

        onDrawBehind {
            drawRect(base)
            if (photo != null && size.width >= 1f && size.height >= 1f) {
                val aspect = size.width / size.height
                val sourceWidth = minOf(photo.width, (photo.height * aspect).toInt().coerceAtLeast(1))
                val sourceHeight = minOf(photo.height, (photo.width / aspect).toInt().coerceAtLeast(1))
                drawImage(photo, srcOffset = androidx.compose.ui.unit.IntOffset((photo.width - sourceWidth) / 2, (photo.height - sourceHeight) / 2),
                    srcSize = androidx.compose.ui.unit.IntSize(sourceWidth, sourceHeight),
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                drawRect(background.copy(alpha = if (keyboard) .57f else .68f))
                return@onDrawBehind
            }
            if (neutral || theme == com.notokens.monolog.data.ThemeMode.OCEAN ||
                theme == com.notokens.monolog.data.ThemeMode.TERRACOTTA) return@onDrawBehind
            drawRect(light)
            drawRect(blue)
            drawRect(lavender)
            drawRect(blush)
            if (dark) {
                translate(size.width * .92f, size.height * if (keyboard) .38f else .2f) {
                    scale(splashScale, splashScale, pivot = Offset.Zero) {
                        drawPath(splash, inkWash)
                        drawCircle(inkWash, 3.6f, Offset(-6f, 14f))
                        drawCircle(inkWash, 2.3f, Offset(-16f, 44f))
                        drawCircle(inkWash, 1.4f, Offset(-29f, 32f))
                    }
                }
                translate(size.width * .08f, size.height * .77f) {
                    scale(-splashScale * .72f, -splashScale * .72f, pivot = Offset.Zero) {
                        drawPath(splash, inkWash)
                        drawCircle(inkWash, 2.7f, Offset(-13f, 25f))
                        drawCircle(inkWash, 1.5f, Offset(-25f, 36f))
                    }
                }
            }
        }
    }
}

enum class GlassAccent { BLUE, LAVENDER, BLUSH }

@Composable
fun Modifier.glassSurface(
    radius: Int = 24,
    accent: GlassAccent? = null,
    shape: Shape = RoundedCornerShape(radius.dp)
): Modifier {
    val dark = LocalMonologDark.current
    val borderColor = PaperPillBorder
    val solid = MaterialTheme.colorScheme.surfaceContainer
    if (LocalReduceTransparency.current) return clip(shape).background(solid).border(1.dp, borderColor, shape)

    val tint = when (accent) {
        GlassAccent.BLUE -> if (dark) Color(0x407C9AF1) else Color(0x80B1C4F3)
        GlassAccent.LAVENDER -> if (dark) Color(0x479D81D9) else Color(0x75CBC0F4)
        GlassAccent.BLUSH -> if (dark) Color(0x4CAD779C) else Color(0xABFFDCCD)
        null -> Color.Transparent
    }
    val sheen = if (dark) listOf(Color(0x31D7DFFF), Color(0x20A3ABCE), Color(0x16ADB9E1))
    else listOf(Color(0xDBFFFFFF), Color(0x95FFFFFF), Color(0x75FFFFFF))
    return clip(shape).drawWithCache {
        val glass = Brush.verticalGradient(sheen)
        val edge = Brush.radialGradient(
            listOf(tint, tint.copy(alpha = 0f)),
            center = Offset(size.width, size.height * .76f),
            radius = (size.maxDimension * .53f).coerceAtLeast(1f)
        )
        onDrawBehind {
            drawRect(glass)
            drawRect(edge)
        }
    }.border(
        1.dp, Brush.verticalGradient(listOf(borderColor, borderColor.copy(alpha = borderColor.alpha * .48f))), shape
    )
}

@Composable
fun Modifier.journalPageAccent(): Modifier {
    if (LocalMonologDark.current || LocalReduceTransparency.current) return this
    return drawWithCache {
        val page = Path().apply {
            moveTo(size.width * .51f, size.height)
            cubicTo(size.width * .73f, size.height * .78f, size.width * .82f, size.height * .43f, size.width, size.height * .29f)
            lineTo(size.width, size.height)
            close()
        }
        val fold = Path().apply {
            moveTo(size.width * .69f, size.height)
            cubicTo(size.width * .82f, size.height * .81f, size.width * .92f, size.height * .59f, size.width, size.height * .57f)
            lineTo(size.width, size.height)
            close()
        }
        val paper = Brush.linearGradient(
            listOf(Color(0xCFFFFFFF), Color(0xB293AAF1)),
            start = Offset(size.width * .67f, size.height * .55f),
            end = Offset(size.width, size.height)
        )
        onDrawBehind {
            drawPath(page, paper)
            drawPath(fold, Color(0x3591A6DF))
        }
    }
}
