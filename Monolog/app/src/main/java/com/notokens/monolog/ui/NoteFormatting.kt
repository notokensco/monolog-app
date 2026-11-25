package com.notokens.monolog.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal enum class NoteFormat(val label: String, val marker: String, val inline: Boolean = false) {
    Bold("Bold", "**", true), Italic("Italic", "_", true), Heading("Heading", "## "),
    Bullets("Bulleted list", "- "), Numbers("Numbered list", "1. "),
    Checklist("Checklist", "- [ ] "), Quote("Quote", "> ");

    fun prefix(index: Int) = if (this == Numbers) "${index + 1}. " else marker
}

private val blockMarker = Regex("^( {0,3})(#{1,6} |[-+*] +\\[[ xX]\\](?: +|$)|[-+*] +|[0-9]{1,9}[.)] +|> ?)")
private val listMarker = Regex("^( {0,3})([-+*]|[0-9]{1,9}[.)]) +(\\[[ xX]\\](?: +|$))?")

internal fun formatNote(value: TextFieldValue, format: NoteFormat): TextFieldValue {
    val text = value.text
    val from = value.selection.min
    val to = value.selection.max
    val marker = format.marker
    if (format.inline) {
        val selected = text.substring(from, to)
        if (from >= marker.length && text.startsWith(marker, from - marker.length) && text.startsWith(marker, to)) {
            val start = from - marker.length
            return TextFieldValue(text.replaceRange(start, to + marker.length, selected), TextRange(start, start + selected.length))
        }
        return TextFieldValue(text.replaceRange(from, to, marker + selected + marker), TextRange(from + marker.length, to + marker.length))
    }

    val start = text.lastIndexOf('\n', from - 1) + 1
    val end = text.indexOf('\n', if (to > from && text[to - 1] == '\n') to - 1 else to).let { if (it < 0) text.length else it }
    val lines = text.substring(start, end).split('\n')
    val matches = lines.map(blockMarker::find)
    val remove = matches.withIndex().all { (index, match) ->
        val existing = match?.groupValues?.get(2).orEmpty()
        when (format) {
            NoteFormat.Checklist -> existing.startsWith("- [")
            NoteFormat.Numbers -> existing.firstOrNull()?.isDigit() == true
            else -> existing == format.prefix(index)
        }
    }
    val replacement = lines.mapIndexed { index, line ->
        val match = matches[index]
        val indent = match?.groupValues?.get(1).orEmpty()
        val existing = match?.groupValues?.get(2).orEmpty()
        val prefix = indent + when {
            remove -> ""
            format == NoteFormat.Checklist && existing.startsWith("- [") -> existing
            else -> format.prefix(index)
        }
        prefix + line.drop(match?.value?.length ?: 0)
    }.joinToString("\n")
    val selection = if (lines.size > 1 || to > end) {
        TextRange(start, start + replacement.length + if (to > end) 1 else 0)
    } else {
        val oldLength = matches.first()?.value?.length ?: 0
        val newLength = replacement.length - (end - start - oldLength)
        fun move(position: Int) = start + newLength + (position - start - oldLength).coerceAtLeast(0)
        TextRange(move(from), move(to))
    }
    return TextFieldValue(text.replaceRange(start, end, replacement), selection)
}

internal fun continueNoteList(previous: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val offset = previous.selection.start
    val source = previous.text
    if (!previous.selection.collapsed || next.composition != null || next.selection != TextRange(offset + 1) ||
        next.text.length != source.length + 1 || next.text.getOrNull(offset) != '\n' ||
        source.getOrNull(offset)?.let { it != '\n' } == true ||
        !next.text.regionMatches(0, source, 0, offset) ||
        !next.text.regionMatches(offset + 1, source, offset, source.length - offset)) return next
    val start = source.lastIndexOf('\n', offset - 1) + 1
    val line = source.substring(start, offset)
    val marker = listMarker.find(line) ?: return next
    if (line.drop(marker.value.length).isBlank()) {
        return TextFieldValue(source.removeRange(start, offset), TextRange(start))
    }
    val (indent, bullet, check) = marker.destructured
    val number = bullet.dropLast(1).toIntOrNull()
    val prefix = indent + (if (number == null) bullet else "${number + 1}${bullet.last()}") +
        if (check.isEmpty()) " " else " [ ] "
    return TextFieldValue(next.text.replaceRange(offset + 1, offset + 1, prefix), TextRange(offset + 1 + prefix.length))
}
