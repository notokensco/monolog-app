package com.notokens.monolog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Capture screens own IME insets once at their root. Keep note editing in that same
 * window: nesting another Android dialog creates a second keyboard resize/inset pass.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CaptureNoteSheet(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    BackHandler(onBack = onDismissRequest)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .48f))
            .clickable(onClickLabel = "Close note editor", onClick = onDismissRequest))
        Surface(
            color = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                .fillMaxWidth().heightIn(max = maxHeight)
                .clickable(remember { MutableInteractionSource() }, indication = null) { }
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                title()
                text()
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
