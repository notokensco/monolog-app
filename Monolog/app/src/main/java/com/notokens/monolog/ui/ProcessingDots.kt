package com.notokens.monolog.ui

import androidx.compose.animation.core.LinearEasing
import kotlin.math.sin
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
            val local = (phase + 1f - index * .135f) % 1f
            val fall = (local / .23f).coerceIn(0f, 1f)
            val visible = ((local - .02f) / .12f).coerceIn(0f, 1f) *
                ((.69f - local) / .24f).coerceIn(0f, 1f)
            val lift = sin(fall * Math.PI).toFloat() * .06f
            drawCircle(color.copy(alpha = .22f), 2.15.dp.toPx(),
                Offset(size.width * (.22f + index * .28f), size.height * .70f))
            drawCircle(color.copy(alpha = visible), 2.6.dp.toPx(),
                Offset(size.width * (.22f + index * .28f), size.height * (.25f + fall * fall * .45f - lift)))
        }
    }
}
