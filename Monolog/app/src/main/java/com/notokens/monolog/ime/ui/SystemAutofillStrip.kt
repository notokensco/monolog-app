package com.notokens.monolog.ime.ui

import android.util.Size
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InlineSuggestion
import android.widget.inline.InlineContentView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
internal fun SystemAutofillStrip(suggestions: List<InlineSuggestion>) {
    val context = LocalContext.current
    val haptics = LocalVibrationHaptics.current
    Row(Modifier.fillMaxWidth().height(46.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (suggestion in suggestions) key(suggestion) {
            val content by produceState<InlineContentView?>(null, suggestion) {
                value = suspendCancellableCoroutine { result ->
                    suggestion.inflate(context, Size(WRAP_CONTENT, WRAP_CONTENT), context.mainExecutor) { view ->
                        if (result.isActive) result.resume(view)
                    }
                }
            }
            content?.let { view ->
                AndroidView(factory = { view }, update = { inline ->
                    inline.setOnClickListener { triggerHapticFeedback(inline, enabled = haptics) }
                })
            }
        }
    }
}
