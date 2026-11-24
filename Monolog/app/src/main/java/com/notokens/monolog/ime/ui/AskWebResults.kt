package com.notokens.monolog.ime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.ai.AiPreview
import com.notokens.monolog.ai.WebLookup
import com.notokens.monolog.ai.WebSearchKind
import com.notokens.monolog.ai.WebSource
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.MonologIcon
import com.notokens.monolog.ui.glassSurface
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import java.net.URI
import kotlinx.coroutines.CancellationException

@Composable
internal fun AskWebResults(result: AiPreview, onApply: (AiPreview) -> Unit) {
    val browser = LocalUriHandler.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (result.text.isNotBlank()) {
            Text(result.text, Modifier.padding(horizontal = 4.dp), color = PaperKeyText,
                style = MaterialTheme.typography.bodyMedium)
            if (result.generatedLocally) {
                Text("On-device summary", Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = PaperKeySubText)
                if (result.insertable) TextButton({ onApply(result) }) { Text("Use answer") }
            }
        }
        if (result.webKind == WebSearchKind.IMAGES && result.sources.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(result.sources, key = { it.thumbnail ?: it.url }) { source ->
                    Column(Modifier.size(width = 106.dp, height = 105.dp).glassSurface(12)
                        .clickable(onClickLabel = "Open image source in browser") {
                            runCatching { browser.openUri(source.url) }
                        }.padding(4.dp)) {
                        SearchThumbnail(source, Modifier.fillMaxWidth().height(66.dp))
                        Text(source.title, color = PaperKeyText, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                        Text(URI.create(source.url).host.orEmpty(), color = PaperKeySubText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp)
                    }
                }
            }
        } else {
            for (source in result.sources) {
                Row(Modifier.fillMaxWidth().glassSurface(12)
                    .clickable(onClickLabel = "Open result in browser") { runCatching { browser.openUri(source.url) } }
                    .padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (source.thumbnail != null) SearchThumbnail(source, Modifier.size(42.dp))
                    Column(Modifier.weight(1f)) {
                        Text(source.title, color = PaperKeyText, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (source.snippet.isNotBlank()) Text(source.snippet, color = PaperKeyText,
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(URI.create(source.url).host.orEmpty(), color = PaperKeySubText, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    MonologIcon(Glyph.ARROW, Modifier.size(15.dp), PaperKeySubText)
                }
            }
        }
    }
}

@Composable
private fun SearchThumbnail(source: WebSource, modifier: Modifier) {
    val uri = source.thumbnail ?: return
    val image by produceState<Bitmap?>(null, uri) {
        value = null
        value = try { WebLookup.thumbnail(uri) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = source.title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else MonologIcon(Glyph.PHOTO, Modifier.size(16.dp), PaperKeySubText)
    }
}
