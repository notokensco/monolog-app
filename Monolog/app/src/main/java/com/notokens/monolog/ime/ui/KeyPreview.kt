package com.notokens.monolog.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class KeyPreview(private val scope: CoroutineScope) {
    data class Preview(val text: String, val bounds: Rect)
    var current by mutableStateOf<Preview?>(null)
        private set
    private var token: Any? = null
    private var task: Job? = null

    fun down(id: Any, preview: Preview, delayMs: Int) {
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
}

internal val LocalKeyPreview = staticCompositionLocalOf<KeyPreview?> { null }

@Composable
internal fun KeyPreviewHost(enabled: Boolean, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { KeyPreview(scope) }
    var root by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.onGloballyPositioned { root = it.boundsInRoot() }) {
        CompositionLocalProvider(LocalKeyPreview provides controller.takeIf { enabled }) { content() }
        if (enabled) KeyPreviewOverlay(controller) { root }
    }
}

@Composable
private fun BoxScope.KeyPreviewOverlay(controller: KeyPreview, rootBounds: () -> Rect) {
    val preview = controller.current ?: return
    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val root = rootBounds()
        val width = if (preview.text.length <= 2) 43.dp else 78.dp
        val x = with(density) { (preview.bounds.center.x - root.left).toDp() }
        val y = with(density) { (preview.bounds.top - root.top).toDp() }
        val shape = RoundedCornerShape(11.dp)
        Box(Modifier.offset(x = (x - width / 2).coerceIn(0.dp, (maxWidth - width).coerceAtLeast(0.dp)),
            y = (y - 44.dp).coerceAtLeast(0.dp))
            .sizeIn(minWidth = width, maxWidth = width, minHeight = 41.dp)
            .shadow(6.dp, shape).background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
            Text(preview.text, color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (preview.text.length <= 2) 23.sp else 12.sp, maxLines = 1)
        }
    }
}
