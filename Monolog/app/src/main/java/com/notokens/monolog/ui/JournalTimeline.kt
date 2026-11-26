package com.notokens.monolog.ui

import com.notokens.monolog.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notokens.monolog.capture.TagMetadataCodec
import com.notokens.monolog.data.MemoryRecord
import com.notokens.monolog.data.Note
import com.notokens.monolog.data.NotePhotoRef
import com.notokens.monolog.ime.vox.VoiceNoteBody
import com.notokens.monolog.memory.MemoryRepository
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun JournalTimeline(
    month: YearMonth,
    records: List<MemoryRecord>,
    notes: List<Note>,
    photos: Map<Long, List<NotePhotoRef>>,
    onMonth: (YearMonth) -> Unit,
    onDay: (LocalDate) -> Unit,
    onNote: (Long) -> Unit,
    onSearch: () -> Unit
) {
    val context = LocalContext.current
    var view by rememberSaveable { mutableStateOf("Timeline") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(month, records, notes, photos, filter) {
        val byNote = notes.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        val byRecord = records.mapNotNull { record ->
            runCatching { LocalDate.parse(record.localDay) }.getOrNull()?.let { it to record }
        }.groupBy({ it.first }, { it.second })
        (byNote.keys + byRecord.keys).filter { YearMonth.from(it) == month }.sortedDescending().mapNotNull { date ->
            val saved = byNote[date].orEmpty()
            val moments = byRecord[date].orEmpty()
            val pictures = saved.flatMap { photos[it.id].orEmpty() }
            val matches = when (filter) {
                "Photos" -> pictures.isNotEmpty()
                "Notes" -> saved.isNotEmpty()
                "Reminders" -> moments.any { it.reminderId != null }
                else -> true
            }
            if (matches) JournalDay(date, saved, moments, pictures) else null
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).glassSurface(20), verticalAlignment = Alignment.CenterVertically) {
            IconControl(Glyph.BACK, "Previous month", { onMonth(month.minusMonths(1)) })
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f).clickable {
                android.app.DatePickerDialog(context, { _, year, value, _ -> onMonth(YearMonth.of(year, value + 1)) },
                    month.year, month.monthValue - 1, 1).show()
            }.padding(vertical = 10.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            IconControl(Glyph.ARROW, "Next month", { onMonth(month.plusMonths(1)) })
        }
        IconControl(Glyph.SEARCH, "Search journal", onSearch, frosted = true)
        IconControl(Glyph.DASHBOARD, "Filter journal", { showFilters = !showFilters }, frosted = true)
    }
    if (showFilters) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("All", "Photos", "Notes", "Reminders").forEach { value ->
            FilterChip(filter == value, { filter = value }, { Text(value) })
        }
    }
    Row(Modifier.fillMaxWidth().glassSurface(22).padding(4.dp)) {
        listOf("Timeline" to Glyph.LAYERS, "Calendar" to Glyph.CLOCK, "Gallery" to Glyph.PHOTO).forEach { (label, icon) ->
            Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                .background(if (view == label) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { view = label }.padding(horizontal = 4.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                MonologIcon(icon, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, fontWeight = if (view == label) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
    when (view) {
        "Calendar" -> {
            val available = days.mapTo(hashSetOf()) { it.date }
            val start = month.atDay(1).dayOfWeek.value - 1
            Column(Modifier.fillMaxWidth().glassSurface(24).padding(8.dp)) {
                Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, color = PaperKeySubText) }
                } }
                (List(start) { 0 } + (1..month.lengthOfMonth()).toList()).chunked(7).forEach { week ->
                    Row {
                        week.forEach { number ->
                            Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                                if (number > 0) {
                                    val day = month.atDay(number)
                                    TextButton({ onDay(day) }, contentPadding = PaddingValues(1.dp)) {
                                        Text("$number" + if (day in available) " •" else "")
                                    }
                                }
                            }
                        }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        "Gallery" -> {
            val images = days.flatMap { it.photos }
            if (images.isEmpty()) JournalIllustratedEmpty("No photos this month", R.drawable.art_library)
            images.take(30).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { photo ->
                        JournalPhotoImage(JournalPhoto.Saved(photo.id), "Journal photo",
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                .clickable { onNote(photo.noteId) }, contentScale = ContentScale.Crop, thumbnailSize = 140.dp)
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        else -> {
            if (days.isEmpty()) JournalIllustratedEmpty("No journal entries this month", R.drawable.art_journal)
            days.forEachIndexed { index, day ->
                key(day.date) {
                    JournalTimelineCard(day, index == 0, index == days.lastIndex, { onDay(day.date) }, onNote)
                }
            }
        }
    }
}

@Composable
private fun JournalIllustratedEmpty(label: String, artwork: Int) {
    Box(Modifier.fillMaxWidth().glassSurface(22).heightIn(min = 95.dp)) {
        CardCornerArtwork(artwork, 115.dp, opacity = .81f)
        Text(label, Modifier.align(Alignment.CenterStart).fillMaxWidth(.72f).padding(16.dp), color = PaperKeySubText)
    }
}

private data class JournalDay(val date: LocalDate, val notes: List<Note>, val records: List<MemoryRecord>,
    val photos: List<NotePhotoRef>)

@Composable
private fun JournalTimelineCard(day: JournalDay, first: Boolean, last: Boolean, onDay: () -> Unit, onNote: (Long) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val rim = MaterialTheme.colorScheme.surface
    val topNote = day.notes.firstOrNull()
    val record = day.records.firstOrNull()
    val memory by produceState<String?>(null, record?.id, record?.revision) {
        value = if (record == null || topNote != null) null else withContext(Dispatchers.Default) {
            runCatching { MemoryRepository.readableSummary(MemoryRepository.decode(record).summary) }.getOrNull()
        }
    }
    val excerpt = remember(topNote) { topNote?.let { markdownExcerpt(TagMetadataCodec.decode(it.body).cleanBody).take(140) } }
    val voice = remember(day.notes) { day.notes.any { VoiceNoteBody.audio(it.body) != null || TagMetadataCodec.decode(it.body).bottomIsVoice } }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val center = Offset(size.width / 2, 28.dp.toPx())
            drawLine(primary.copy(alpha = .35f), Offset(center.x, if (first) center.y else -12.dp.toPx()),
                Offset(center.x, if (last) center.y else size.height + 12.dp.toPx()), 1.4.dp.toPx())
            drawCircle(primary, 5.dp.toPx(), center)
            drawCircle(rim, 7.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }
        Box(Modifier.weight(1f).glassSurface(24)) {
            val hasPhotos = day.photos.isNotEmpty()
            if (!hasPhotos) {
                val artwork = when {
                    voice -> R.drawable.art_audio
                    record?.source == MemoryRepository.MOOD_SOURCE -> R.drawable.art_mood_okay
                    day.notes.isEmpty() && day.records.any { it.reminderId != null } -> R.drawable.art_reminder
                    else -> R.drawable.art_journal
                }
                CardCornerArtwork(artwork, 125.dp, opacity = .65f)
            }
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val date = day.date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
                val label = if (day.date == LocalDate.now()) "Today" else day.date.format(DateTimeFormatter.ofPattern("EEEE"))
                Text("$date · $label", color = PaperKeySubText, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(if (hasPhotos) 1f else .74f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(topNote?.title ?: memory?.take(70) ?: "Saved moments", color = PaperKeyText,
                            style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!excerpt.isNullOrBlank()) Text(excerpt, color = PaperKeySubText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    day.photos.take(if (day.notes.size > 1) 2 else 1).forEach { photo ->
                        JournalPhotoImage(JournalPhoto.Saved(photo.id), "Photo from this day",
                            Modifier.size(68.dp).clip(RoundedCornerShape(11.dp)).clickable { onNote(photo.noteId) },
                            contentScale = ContentScale.Crop, thumbnailSize = 96.dp)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (day.photos.isNotEmpty()) Text("Photos", color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
                    if (day.notes.isNotEmpty()) Text("Notes", color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
                    if (voice) Text("Voice", color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
                    if (day.records.any { it.reminderId != null }) Text("Reminders", color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider(color = primary.copy(alpha = .15f))
                TextButton(onDay, contentPadding = PaddingValues(0.dp)) {
                    Text("View day")
                    Spacer(Modifier.width(7.dp))
                    MonologIcon(Glyph.ARROW, Modifier.size(14.dp))
                }
            }
        }
    }
}
