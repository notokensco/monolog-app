package com.notokens.monolog.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ProcessingDots(modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "Processing").animateFloat(
        0f, 1f, infiniteRepeatable(tween(durationMillis = 1150, easing = LinearEasing)), label = "Falling dots"
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(width = 34.dp, height = 20.dp).semantics { contentDescription = "Processing answer" }) {
        repeat(3) { index ->
            val elapsed = (phase - index * .17f) / .22f
            val progress = elapsed.coerceIn(0f, 1f)
            val opacity = if (elapsed < 0f) .24f else 1f
            drawCircle(color.copy(alpha = opacity), 2.6.dp.toPx(),
                Offset(size.width * (.22f + index * .28f), size.height * (.27f + progress * progress * .48f)))
        }
    }
}
