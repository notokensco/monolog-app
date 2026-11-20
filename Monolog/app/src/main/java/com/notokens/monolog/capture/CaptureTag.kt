package com.notokens.monolog.capture

import android.graphics.Bitmap

internal data class CaptureTag(
    val xRatio: Float,
    val yRatio: Float,
    val text: String,
    val isVoice: Boolean = false
)

internal object PendingCaptureHolder {
    var bitmap: Bitmap? = null
}
