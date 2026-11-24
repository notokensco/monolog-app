package com.notokens.monolog.ime.ui

import androidx.compose.animation.core.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.aiKeyTracing(enabled: Boolean, corner: Dp): Modifier {
    if (!enabled) return this
    val position by rememberInfiniteTransition(label = "AI space key").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3700, easing = LinearEasing)), label = "AI trace position")
    val accent = MaterialTheme.colorScheme.primary
    return drawWithCache {
        val path = Path().apply {
            val inset = 1.4.dp.toPx()
            addRoundRect(RoundRect(Rect(inset, inset, size.width - inset, size.height - inset),
                CornerRadius((corner.toPx() - inset).coerceIn(0f, (size.minDimension / 2 - inset).coerceAtLeast(0f)))))
        }
        val measure = PathMeasure().apply { setPath(path, true) }
        val trail = Path()
        onDrawWithContent {
            drawContent()
            val length = measure.length
            for (i in 9 downTo 0) {
                val distance = ((position - i * .022f + 1f) % 1f) * length
                val end = distance + length * .025f
                trail.reset()
                measure.getSegment(distance, end.coerceAtMost(length), trail)
                if (end > length) measure.getSegment(0f, end - length, trail)
                val color = if (i <= 2) accent else Color.White
                drawPath(trail, color.copy(alpha = 1f - i * .09f),
                    style = Stroke(1.65.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
