package com.notokens.monolog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.notokens.monolog.R
import com.notokens.monolog.capture.TagMetadataCodec
import com.notokens.monolog.data.JournalHobbies
import com.notokens.monolog.data.MemoryRecord
import com.notokens.monolog.data.MonologDatabase
import com.notokens.monolog.data.Note
import com.notokens.monolog.data.hobbyStreak
import com.notokens.monolog.memory.MemoryRepository
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TodayWindow(val date: LocalDate, val zone: ZoneId, val slot: Int) {
    fun dayAt(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    fun slotAt(timestamp: Long): Int = Instant.ofEpochMilli(timestamp).atZone(zone).hour / 3
}

private fun ZonedDateTime.window() = TodayWindow(toLocalDate(), zone, hour / 3)

@Composable
internal fun rememberTodayWindow(): TodayWindow {
    val window by produceState(ZonedDateTime.now().window()) {
        while (true) {
            val now = ZonedDateTime.now()
            value = now.window()
            val next = now.toLocalDate().atStartOfDay().plusHours(((now.hour / 3) + 1) * 3L).atZone(now.zone)
            delay(Duration.between(now, next).toMillis().coerceIn(250, 30_000))
        }
    }
    return window
}

private data class TodayCounts(val days: Int, val words: Long)

@Composable
internal fun TodayJournalStats(notes: List<Note>, records: List<MemoryRecord>, zone: ZoneId) {
    val draftRecords = remember(records) { records.filter { it.source == "Drafted" } }
    val counts by produceState<TodayCounts?>(null, notes, draftRecords, zone) {
        value = withContext(Dispatchers.Default) {
            val days = draftRecords.mapTo(hashSetOf()) { it.localDay }
            val words = android.icu.text.BreakIterator.getWordInstance()
            fun count(text: String): Long {
                words.setText(text)
                var total = 0L
                var start = words.first()
                var end = words.next()
                while (end != android.icu.text.BreakIterator.DONE) {
                    if ((start until end).any { text[it].isLetterOrDigit() }) total++
                    start = end
                    end = words.next()
                }
                return total
            }
            val noteWords = notes.sumOf { note ->
                days.add(Instant.ofEpochMilli(note.createdAt).atZone(zone).toLocalDate().toString())
                val decoded = TagMetadataCodec.decode(note.body)
                val body = if (decoded.hasMetadata)
                    (listOf(decoded.bottomComment, decoded.additionalBody) + decoded.tags.map { it.text })
                        .distinct().joinToString(" ") else decoded.cleanBody
                count(markdownExcerpt(body, limit = body.length))
            }
            val draftWords = draftRecords.sumOf { row ->
                runCatching { count(MemoryRepository.decode(row).summary) }.getOrDefault(0L)
            }
            TodayCounts(days.size, noteWords + draftWords)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val format = remember { NumberFormat.getIntegerInstance() }
        TodayStat(counts?.let { format.format(it.days) } ?: "—", "Days journaled", R.drawable.art_calendar, Modifier.weight(1f))
        TodayStat(counts?.let { format.format(it.words) } ?: "—", "Words stored", R.drawable.art_words, Modifier.weight(1f))
    }
}

@Composable
private fun TodayStat(value: String, label: String, artwork: Int, modifier: Modifier) {
    Box(modifier.glassSurface(22, GlassAccent.BLUE).heightIn(min = 72.dp)) {
        CardCornerArtwork(artwork, 85.dp, opacity = .78f)
        Column(Modifier.fillMaxWidth(.72f).padding(horizontal = 13.dp, vertical = 10.dp)) {
            Text(value, color = PaperKeyText, style = MaterialTheme.typography.titleLarge)
            Text(label, color = PaperKeySubText, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private val moodArtwork = linkedMapOf(
    "Great" to R.drawable.art_mood_great, "Good" to R.drawable.art_mood_good, "Okay" to R.drawable.art_mood_okay,
    "Low" to R.drawable.art_mood_low, "Struggling" to R.drawable.art_mood_struggling
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayMoodCheckIn(window: TodayWindow, records: List<MemoryRecord>, repository: MemoryRepository) {
    val checkIns = remember(window, records) {
        records.filter { it.source == MemoryRepository.MOOD_SOURCE && it.localDay == window.date.toString() }
    }
    val current = remember(checkIns, window) {
        checkIns.firstOrNull { window.slotAt(it.timestamp) == window.slot }
            ?.let { runCatching { MemoryRepository.decode(it).mood }.getOrNull() }
    }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember(window) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().glassSurface(24, GlassAccent.LAVENDER)) {
        CardCornerArtwork(moodArtwork[current] ?: R.drawable.art_mood_okay, 128.dp, opacity = .78f)
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            SettingsCard("How are you feeling?", embedded = true) {
                Text(if (current == null) "Check in whenever you like. A new check-in opens every three hours."
                    else "Saved for this check-in. You can change your answer.", Modifier.fillMaxWidth(.76f), color = PaperKeySubText,
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(Modifier.fillMaxWidth(.80f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    moodArtwork.forEach { (mood, artwork) ->
                        FilterChip(selected = mood == current, enabled = !saving, label = { Text(mood) },
                            leadingIcon = { ScreenArtwork(artwork, Modifier.size(24.dp)) }, onClick = {
                            if (mood == current || saving) return@FilterChip
                            saving = true
                            error = false
                            scope.launch {
                                try { repository.checkIn(mood, ZonedDateTime.now()) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = true }
                                finally { saving = false }
                            }
                        })
                    }
                }
                if (error) Text("Couldn’t save your check-in. Try again.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun TodayHobbies(db: MonologDatabase, date: LocalDate) {
    val repository = remember(db) { JournalHobbies(db) }
    val hobbies by repository.entries.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxWidth().glassSurface(24, GlassAccent.BLUSH)) {
        CardCornerArtwork(R.drawable.art_streak, 127.dp, opacity = if (hobbies.isEmpty()) .84f else .52f)
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            SettingsCard("Hobbies", embedded = true) {
                if (hobbies.isEmpty()) Text("What do you enjoy doing? Add a hobby to check in and build a daily streak.",
                    Modifier.fillMaxWidth(.72f), color = PaperKeySubText)
                for (entry in hobbies) {
                    val days = remember(entry.days) { entry.days.toSet() }
                    val done = date.toString() in days
                    val streak = remember(days, date) { hobbyStreak(days, date) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.hobby.name, color = PaperKeyText, fontWeight = FontWeight.SemiBold)
                            Text(if (done) "Done today · $streak-day streak"
                                else if (streak > 0) "Did you do it today? · $streak-day streak" else "Did you do it today?",
                                color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                            StreakWeek(days, date)
                        }
                        OutlinedButton(enabled = !pending, onClick = {
                            pending = true
                            error = null
                            scope.launch {
                                try { repository.setCompleted(entry.hobby.id, date, !done) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Couldn’t update the hobby. Try again." }
                                finally { pending = false }
                            }
                        }) { Text(if (done) "Undo" else "Yes") }
                    }
                }
                if (adding) {
                    OutlinedTextField(name, { name = it.take(40); error = null }, Modifier.fillMaxWidth(),
                        label = { Text("Hobby name") }, shape = CircleShape, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !pending, onClick = { adding = false; name = ""; error = null }) { Text("Cancel") }
                        Button(enabled = name.isNotBlank() && !pending, onClick = {
                            val title = name
                            pending = true
                            scope.launch {
                                try {
                                    if (repository.add(title)) { adding = false; name = ""; error = null }
                                    else error = "You’re already tracking this hobby."
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Couldn’t save the hobby. Try again." }
                                finally { pending = false }
                            }
                        }) { Text("Save hobby") }
                    }
                } else TextButton(onClick = { adding = true; error = null }) { Text("Add a hobby") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun StreakWeek(days: Set<String>, date: LocalDate) {
    val sunday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(7) { index ->
            val day = sunday.plusDays(index.toLong())
            val done = day.toString() in days
            val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val status = when {
                done -> "completed"
                day.isAfter(date) -> "upcoming"
                else -> "not completed"
            }
            Column(
                Modifier.widthIn(min = 15.dp).clearAndSetSemantics { contentDescription = "$weekday, $status" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(Modifier.size(8.dp).background(if (done) colors.primary else colors.primary.copy(alpha = .17f), CircleShape))
                Text(weekday.take(1), color = PaperKeySubText, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
