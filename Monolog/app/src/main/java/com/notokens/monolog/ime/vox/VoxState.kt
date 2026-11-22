package com.notokens.monolog.ime.vox

enum class VoxStatus {
    IDLE,
    LISTENING,
    PAUSED,
    PROCESSING,
    ERROR,
    PERMISSION_REQUIRED,
    UNAVAILABLE
}

data class VoxState(
    val status: VoxStatus = VoxStatus.IDLE,
    val transcript: String = "",
    val rmsLevel: Float = 0f,
    val errorMessage: String? = null,
    val language: String = "English",
    val finalSegment: String = "",
    val usesWhisper: Boolean = false,
    val partialTranscript: String = ""
)
