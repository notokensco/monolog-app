package com.notokens.monolog.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class KeyPreview(private val scope: CoroutineScope) {
    data class Preview(val text: String, val bounds: Rect)
    data class Alternates(val choices: List<String>, val left: Float, val top: Float, val cellWidth: Float)
    var rootBounds by mutableStateOf(Rect.Zero)
    var current by mutableStateOf<Preview?>(null)
        private set
    var alternates by mutableStateOf<Alternates?>(null)
        private set
    var highlighted by mutableIntStateOf(0)
        private set
    private var token: Any? = null
    private var task: Job? = null

    fun down(id: Any, preview: Preview, delayMs: Int) {
        if (alternates != null) return
        task?.cancel()
        if (token !== id) current = null
        token = id
        task = scope.launch {
            delay(delayMs.toLong())
            current = preview
        }
    }

    fun up(id: Any, preview: Preview?) {
        if (token !== id) return
        task?.cancel()
        current = preview
        if (preview == null) token = null
        else task = scope.launch {
            delay(105)
            current = null
            token = null
        }
    }

    fun hold(id: Any, bounds: Rect, choices: List<String>, cellWidth: Float, height: Float, gap: Float): Boolean {
        if (alternates != null || rootBounds.width <= 0f || bounds.width <= 0f) return false
        task?.cancel()
        token = id
        current = null
        val width = cellWidth.coerceAtMost(rootBounds.width / choices.size)
        val left = (bounds.center.x - width / 2).coerceIn(rootBounds.left,
            (rootBounds.right - width * choices.size).coerceAtLeast(rootBounds.left))
        alternates = Alternates(choices, left, (bounds.top - height - gap).coerceAtLeast(rootBounds.top), width)
        highlighted = 0
        return true
    }

    fun move(id: Any, position: Offset, cancelBelow: Float) {
        val row = alternates ?: return
        if (token !== id) return
        highlighted = if (position.y > cancelBelow) -1 else
            ((position.x - row.left) / row.cellWidth).toInt().coerceIn(row.choices.indices)
    }

    fun finishHold(id: Any, accept: Boolean): String? {
        val row = alternates ?: return null
        if (token !== id) return null
        alternates = null
        up(id, null)
        return row.choices.getOrNull(highlighted).takeIf { accept }
    }

    fun dismiss() {
        task?.cancel()
        token = null
        current = null
        alternates = null
    }
}

internal val LocalKeyPreview = staticCompositionLocalOf<KeyPreview?> { null }

@Composable
internal fun KeyPreviewHost(enabled: Boolean, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { KeyPreview(scope) }
    LaunchedEffect(enabled) { if (!enabled) controller.dismiss() }
    Box(Modifier.onGloballyPositioned { controller.rootBounds = it.boundsInRoot() }) {
        CompositionLocalProvider(LocalKeyPreview provides controller.takeIf { enabled }) { content() }
        if (enabled) KeyPreviewOverlay(controller)
    }
}

@Composable
private fun BoxScope.KeyPreviewOverlay(controller: KeyPreview) {
    val row = controller.alternates
    if (row != null) {
        val density = LocalDensity.current
        val root = controller.rootBounds
        val shape = RoundedCornerShape(11.dp)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.absoluteOffset(
                x = with(density) { (row.left - root.left).toDp() },
                y = with(density) { (row.top - root.top).toDp() }
            ).shadow(6.dp, shape).background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)) {
                row.choices.forEachIndexed { index, choice ->
                    val selected = controller.highlighted == index
                    Box(Modifier.width(with(density) { row.cellWidth.toDp() }).height(42.dp)
                        .padding(2.dp).clip(RoundedCornerShape(9.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else
                            MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Text(choice, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else
                            MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, maxLines = 1)
                    }
                }
            }
        }
        return
    }
    val preview = controller.current ?: return
    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val root = controller.rootBounds
        val width = if (preview.text.length <= 2) 43.dp else 78.dp
        val x = with(density) { (preview.bounds.center.x - root.left).toDp() }
        val y = with(density) { (preview.bounds.top - root.top).toDp() }
        val shape = RoundedCornerShape(11.dp)
        Box(Modifier.absoluteOffset(x = (x - width / 2).coerceIn(0.dp, (maxWidth - width).coerceAtLeast(0.dp)),
            y = (y - 44.dp).coerceAtLeast(0.dp))
            .sizeIn(minWidth = width, maxWidth = width, minHeight = 41.dp)
            .shadow(6.dp, shape).background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
            Text(preview.text, color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (preview.text.length <= 2) 23.sp else 12.sp, maxLines = 1)
        }
    }
}
