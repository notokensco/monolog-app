package com.notokens.monolog.ime.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A steady soft halo keeps active modes visible without a pulsing animation. */
@Composable
internal fun Modifier.activeKeyGlow(active: Boolean, circular: Boolean = false): Modifier {
    if (!active) return this
    return drawWithContent {
        drawContent()
        val radius = size.minDimension / 2f
        val tint = Color(0xFF969FFF)
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = .34f), tint.copy(alpha = 0f)),
            center, radius.coerceAtLeast(1f)), radius, center)
        val inset = 5.dp.toPx()
        val ringSize = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f))
        val corner = if (circular) ringSize.minDimension / 2 else 12.dp.toPx()
        drawRoundRect(tint.copy(alpha = .14f), Offset(inset, inset), ringSize, CornerRadius(corner))
        drawRoundRect(tint.copy(alpha = .75f), Offset(inset, inset), ringSize, CornerRadius(corner),
            style = Stroke(1.dp.toPx()))
    }
}
