package com.notokens.monolog.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.PhotoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer

private val markdownParser = Parser.builder().build()
private val markdownText = TextContentRenderer.builder().build()
private val whitespace = Regex("\\s+")

internal fun markdownExcerpt(source: String): String =
    markdownText.render(markdownParser.parse(source.take(2048))).trim().replace(whitespace, " ")

@Composable
internal fun JournalMarkdown(source: String, modifier: Modifier = Modifier) {
    var document by remember(source) { mutableStateOf<Node?>(null) }
    LaunchedEffect(source) {
        document = withContext(Dispatchers.Default) { markdownParser.parse(source) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        document?.let { for (child in it.children()) MarkdownBlock(child) }
    }
}

@Composable
private fun MarkdownBlock(node: Node) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    when (node) {
        is Heading -> Text(
            inlineMarkdown(node), color = colors.onSurface, fontWeight = FontWeight.SemiBold,
            style = when (node.level) {
                1 -> type.headlineSmall
                2 -> type.titleLarge
                else -> type.titleMedium
            }
        )
        is Paragraph -> Text(inlineMarkdown(node), color = colors.onSurface, style = type.bodyLarge)
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.children().forEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (node is OrderedList) "${(node.markerStartNumber ?: 1) + index}." else "•", color = colors.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (child in item.children()) MarkdownBlock(child)
                    }
                }
            }
        }
        is BlockQuote -> Column(
            Modifier.fillMaxWidth().glassSurface(14, GlassAccent.LAVENDER).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (child in node.children()) MarkdownBlock(child)
        }
        is FencedCodeBlock -> MarkdownCode(node.literal)
        is IndentedCodeBlock -> MarkdownCode(node.literal)
        is HtmlBlock -> Text(node.literal, color = colors.onSurface, style = type.bodyMedium)
        is ThematicBreak -> HorizontalDivider()
    }
}

@Composable
private fun MarkdownCode(source: String) {
    Text(
        source.trimEnd('\n'), Modifier.fillMaxWidth().glassSurface(12).padding(12.dp),
        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium
    )
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

private fun inlineMarkdown(node: Node): AnnotatedString =
    AnnotatedString.Builder().apply { for (child in node.children()) appendMarkdown(child) }.toAnnotatedString()

private fun AnnotatedString.Builder.appendMarkdown(node: Node) {
    fun appendChildren() { for (child in node.children()) appendMarkdown(child) }
    when (node) {
        is org.commonmark.node.Text -> append(node.literal)
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendChildren() }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendChildren() }
        is HardLineBreak, is SoftLineBreak -> append('\n')
        is HtmlInline -> append(node.literal)
        is Link -> {
            val uri = Uri.parse(node.destination)
            if (uri.scheme in setOf("https", "http") && !uri.host.isNullOrEmpty()) {
                withLink(LinkAnnotation.Url(node.destination, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                    appendChildren()
                }
            } else appendChildren()
        }
        else -> appendChildren()
    }
}

internal sealed interface JournalPhoto {
    data class Saved(val id: Long) : JournalPhoto
    data class Picked(val uri: String) : JournalPhoto
}

@Composable
internal fun JournalPhotoImage(
    photo: JournalPhoto,
    description: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    thumbnailSize: Dp? = null
) {
    val context = LocalContext.current
    // Square crops need some extra resolution for the short side of portrait photos.
    val maxEdge = thumbnailSize?.let { with(LocalDensity.current) { it.roundToPx() * 2 } } ?: 1800
    var image by remember(photo, maxEdge) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photo, maxEdge) {
        image = withContext(Dispatchers.IO) {
            runCatching {
                when (photo) {
                    is JournalPhoto.Saved -> MonologData.repository(context).photoImage(photo.id)?.let {
                        PhotoCodec.decode(it, maxEdge)
                    }
                    is JournalPhoto.Picked -> PhotoCodec.decode(context.contentResolver, Uri.parse(photo.uri), maxEdge)
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, description, modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            MonologIcon(Glyph.PHOTO, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun JournalPhotoStrip(photos: List<JournalPhoto>, onRemove: ((JournalPhoto) -> Unit)? = null) {
    var opened by remember { mutableStateOf<JournalPhoto?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(photos, key = { _, photo -> photo.toString() }) { index, photo ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                JournalPhotoImage(
                    photo, "Photo ${index + 1}",
                    Modifier.size(104.dp).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "View photo") { opened = photo },
                    thumbnailSize = 104.dp
                )
                if (onRemove != null) TextButton(onClick = { onRemove(photo) }) { Text("Remove") }
            }
        }
    }
    opened?.let { photo ->
        Dialog(onDismissRequest = { opened = null }) {
            Column(
                Modifier.fillMaxWidth().glassSurface(20).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                JournalPhotoImage(photo, "Attached photo", Modifier.fillMaxWidth().heightIn(max = 460.dp), ContentScale.Fit)
                TextButton(onClick = { opened = null }) { Text("Close") }
            }
        }
    }
}
