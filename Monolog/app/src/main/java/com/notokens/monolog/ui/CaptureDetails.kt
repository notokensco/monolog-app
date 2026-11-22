package com.notokens.monolog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notokens.monolog.capture.TagMetadataCodec
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText

@Composable
internal fun CaptureDetails(capture: TagMetadataCodec.DecodedCapture) {
    val comment = capture.bottomComment.ifBlank {
        capture.cleanBody.substringBefore("### Tags").substringBefore("**Category:**").trim()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (comment.isNotBlank() || capture.bottomAudio != null) Column(Modifier.fillMaxWidth().glassSurface(22).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonologIcon(if (capture.bottomIsVoice) Glyph.MIC else Glyph.BOOK, Modifier.size(18.dp))
                Text(if (capture.bottomIsVoice) "Voice note" else "Your note",
                    style = MaterialTheme.typography.labelLarge, color = PaperKeySubText)
            }
            if (comment.isNotBlank()) Text(comment, color = PaperKeyText, style = MaterialTheme.typography.bodyLarge)
            capture.bottomAudio?.let { VoiceNotePlayback(it) }
        }
        capture.tags.forEach { tag ->
            Row(Modifier.fillMaxWidth().glassSurface(20).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                MonologIcon(if (tag.isVoice) Glyph.MIC else Glyph.PIN, Modifier.size(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (tag.isVoice) "Pinned voice note" else "Pinned note",
                        color = PaperKeySubText, style = MaterialTheme.typography.labelMedium)
                    Text(tag.text, color = PaperKeyText, style = MaterialTheme.typography.bodyMedium)
                    tag.audio?.let { VoiceNotePlayback(it) }
                }
            }
        }
    }
}
