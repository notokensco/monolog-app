package com.notokens.monolog.capture

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

data class CaptureTag(
    val xRatio: Float,
    val yRatio: Float,
    val text: String,
    val isVoice: Boolean = false,
    val audio: String? = null
)

object PendingCaptureHolder {
    var bitmap: Bitmap? = null
}

object TagMetadataCodec {
    private const val PREFIX = "<!-- MONOLOG_CAPTURE:"
    private const val SUFFIX = "-->"
    const val ANALYSIS_HEADING = "### Detected in the screenshot"
    private const val LEGACY_ANALYSIS_HEADING = "## Text from screenshot"
    private val analysisHeading = Regex("(?m)^(?:$ANALYSIS_HEADING|$LEGACY_ANALYSIS_HEADING)[ \t]*\r?$")
    private const val LEGACY_PREFIX = "<!-- MONOLOG_TAGS:"

    fun encodeCapture(tags: List<CaptureTag>, bottomIsVoice: Boolean, bottomComment: String, additionalBody: String,
        bottomAudio: String? = null): String {
        val displayBody = buildString {
            if (bottomComment.isNotBlank()) appendLine(bottomComment).appendLine()
            if (tags.isNotEmpty()) {
                appendLine("### Tags (${tags.size}/5)")
                tags.forEach { tag ->
                    val label = if (tag.isVoice) "Voice note" else "Pinned note"
                    appendLine("- **$label:** ${tag.text}")
                }
                appendLine()
            }
            append(visibleBody(additionalBody))
        }
        return encode(displayBody, tags, bottomIsVoice, bottomComment, additionalBody.trim(), bottomAudio)
    }

    private fun encode(
        displayBody: String,
        tags: List<CaptureTag>,
        bottomIsVoice: Boolean,
        bottomComment: String,
        additionalBody: String, bottomAudio: String? = null
    ): String {
        val root = JSONObject()
        val tagsArray = JSONArray()
        tags.forEach { tag ->
            val obj = JSONObject().apply {
                put("x", tag.xRatio.toDouble())
                put("y", tag.yRatio.toDouble())
                put("text", tag.text)
                put("isVoice", tag.isVoice)
                if (tag.audio != null) put("audio", tag.audio)
            }
            tagsArray.put(obj)
        }
        root.put("tags", tagsArray)
        root.put("bottomIsVoice", bottomIsVoice)
        if (bottomAudio != null) root.put("bottomAudio", bottomAudio)
        root.put("additionalBody", additionalBody)
        if (bottomComment.isNotBlank()) {
            root.put("bottomComment", bottomComment)
        }
        val metadata = root.toString().replace("<", "\\u003c").replace(">", "\\u003e")
        return "${displayBody.trimEnd()}\n\n$PREFIX$metadata$SUFFIX"
    }

    data class DecodedCapture(
        val cleanBody: String,
        val tags: List<CaptureTag>,
        val bottomIsVoice: Boolean,
        val bottomComment: String,
        val additionalBody: String = "",
        val hasMetadata: Boolean = false,
        val bottomAudio: String? = null
    ) {
        val analysis: String
            get() = analysisHeading.find(additionalBody)?.let { heading ->
                additionalBody.substring(heading.range.last + 1).trim()
            }.orEmpty()
    }

    private fun visibleBody(body: String): String =
        body.take(analysisHeading.find(body)?.range?.first ?: body.length).trimEnd()

    fun replaceVisibleBody(original: String, visible: String): String {
        val existing = decode(original)
        if (!existing.hasMetadata && existing.analysis.isBlank()) return com.notokens.monolog.ime.vox.VoiceNoteBody.attach(
            visible, com.notokens.monolog.ime.vox.VoiceNoteBody.audio(original))
        return encode(visible, existing.tags, existing.bottomIsVoice, existing.bottomComment, existing.additionalBody, existing.bottomAudio)
    }

    fun replaceCaptureComment(original: String, comment: String): String {
        val existing = decode(original)
        return encodeCapture(existing.tags, existing.bottomIsVoice, comment, existing.additionalBody, existing.bottomAudio)
    }

    fun withoutAudio(body: String): String {
        val capture = decode(body)
        if (capture.hasMetadata && (capture.bottomAudio != null || capture.tags.any { it.audio != null })) {
            return encodeCapture(capture.tags.map { it.copy(audio = null) }, capture.bottomIsVoice,
                capture.bottomComment, capture.additionalBody)
        }
        return com.notokens.monolog.ime.vox.VoiceNoteBody.strip(body)
    }

    fun decode(rawBody: String): DecodedCapture {
        var start = rawBody.lastIndexOf(PREFIX)
        if (start != -1) {
            val end = rawBody.indexOf(SUFFIX, start)
            if (end != -1) {
                val jsonStr = rawBody.substring(start + PREFIX.length, end).trim()
                val clean = rawBody.substring(0, start).trimEnd()
                return parseJson(clean, jsonStr)
            }
        }
        start = rawBody.lastIndexOf(LEGACY_PREFIX)
        if (start != -1) {
            val end = rawBody.indexOf(SUFFIX, start)
            if (end != -1) {
                val jsonStr = rawBody.substring(start + LEGACY_PREFIX.length, end).trim()
                val clean = rawBody.substring(0, start).trimEnd()
                val tags = parseLegacyArray(jsonStr)
                return DecodedCapture(visibleBody(clean), tags, false, "", clean, hasMetadata = true)
            }
        }
        val text = com.notokens.monolog.ime.vox.VoiceNoteBody.strip(rawBody)
        return DecodedCapture(visibleBody(text), emptyList(), false, "", text)
    }

    private fun parseJson(cleanBody: String, jsonStr: String): DecodedCapture {
        val tags = mutableListOf<CaptureTag>()
        var bottomIsVoice = false
        var bottomComment = ""
        var additionalBody = ""
        var bottomAudio: String? = null
        runCatching {
            val obj = JSONObject(jsonStr)
            val array = obj.optJSONArray("tags")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    tags.add(
                        CaptureTag(
                            xRatio = item.optDouble("x", 0.5).toFloat(),
                            yRatio = item.optDouble("y", 0.5).toFloat(),
                            text = item.optString("text", ""),
                            isVoice = item.optBoolean("isVoice", false),
                            audio = item.optString("audio").takeIf { it.isNotBlank() && it.length <= 225_000 }
                        )
                    )
                }
            }
            bottomIsVoice = obj.optBoolean("bottomIsVoice", false)
            bottomComment = obj.optString("bottomComment", "")
            bottomAudio = obj.optString("bottomAudio").takeIf { it.isNotBlank() && it.length <= 225_000 }
            additionalBody = obj.optString("additionalBody", "")
        }
        return DecodedCapture(visibleBody(cleanBody), tags, bottomIsVoice, bottomComment, additionalBody.ifBlank {
            if (analysisHeading.containsMatchIn(cleanBody)) cleanBody else ""
        }, hasMetadata = true, bottomAudio = bottomAudio)
    }

    private fun parseLegacyArray(jsonStr: String): List<CaptureTag> {
        val tags = mutableListOf<CaptureTag>()
        runCatching {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                tags.add(
                    CaptureTag(
                        xRatio = item.optDouble("x", 0.5).toFloat(),
                        yRatio = item.optDouble("y", 0.5).toFloat(),
                        text = item.optString("text", ""),
                        isVoice = item.optBoolean("isVoice", false)
                    )
                )
            }
        }
        return tags
    }
}
