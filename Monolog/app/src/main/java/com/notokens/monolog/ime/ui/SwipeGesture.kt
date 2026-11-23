package com.notokens.monolog.ime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.notokens.monolog.ime.QwertyLayout
import com.notokens.monolog.ime.SwipePoint

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun swipeTypingModifier(
    enabled: Boolean,
    rows: List<String> = QwertyLayout.rows,
    onSwipe: (List<SwipePoint>) -> Unit
): Modifier {
    if (!enabled) return Modifier
    val onComplete by rememberUpdatedState(onSwipe)
    val points = remember { mutableStateListOf<Offset>() }
    val color = MaterialTheme.colorScheme.primary.copy(alpha = .72f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return Modifier
        .drawWithContent {
            drawContent()
            if (points.size > 1) {
                val trailLength = 84.dp.toPx()
                clipRect {
                    var remaining = trailLength
                    for (index in points.lastIndex downTo 1) {
                        val end = points[index]
                        val start = points[index - 1]
                        val distance = (end - start).getDistance()
                        if (distance <= 0f) continue
                        val fraction = (remaining / distance).coerceAtMost(1f)
                        drawLine(
                            color.copy(alpha = color.alpha * (remaining / trailLength)),
                            end, end + (start - end) * fraction, 4.dp.toPx(), StrokeCap.Round
                        )
                        remaining -= distance
                        if (remaining <= 0f) break
                    }
                }
            }
        }
        .pointerInput(rtl, rows) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                if (width <= 0f || height <= 0f) return@awaitEachGesture
                fun normalize(point: Offset): SwipePoint {
                    val x = if (rtl) width - point.x else point.x
                    return SwipePoint(x * QwertyLayout.width / width,
                        point.y * QwertyLayout.rowSpacing * QwertyLayout.rows.size / height)
                }
                val firstLetter = QwertyLayout.letterAt(normalize(down.position), rows) ?: return@awaitEachGesture

                var swiping = false
                val slop = maxOf(viewConfiguration.touchSlop, width / QwertyLayout.width * .55f)
                fun record(point: Offset) {
                    if ((point - points.last()).getDistance() < 1.5.dp.toPx()) return
                    if (points.size >= 192) {
                        val sampled = points.filterIndexed { index, _ -> index % 2 == 0 }
                        points.clear()
                        points.addAll(sampled)
                    }
                    points.add(point)
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.singleOrNull() ?: break
                        if (change.id != down.id || change.isConsumed) break
                        if (!swiping) {
                            if (change.uptimeMillis - down.uptimeMillis >=
                                viewConfiguration.longPressTimeoutMillis) break
                            // Fast glides may arrive as a down and displaced up with no move frame.
                            val letter = QwertyLayout.letterAt(normalize(change.position), rows)
                            if ((change.position - down.position).getDistance() < slop || letter == firstLetter || letter == null) {
                                if (!change.pressed) break
                                continue
                            }
                            swiping = true
                            points.add(down.position)
                        }
                        // Claim the drag before the starting key can treat the release as a tap.
                        change.consume()
                        change.historical.forEach { sample -> record(sample.position) }
                        if (!change.pressed) {
                            if (change.position != points.last()) points.add(change.position)
                            onComplete(points.map(::normalize))
                            break
                        }
                        record(change.position)
                    }
                } finally {
                    points.clear()
                }
            }
        }
}
