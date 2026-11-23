package com.notokens.monolog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notokens.monolog.data.MemoryRecord
import com.notokens.monolog.data.MonologDatabase
import com.notokens.monolog.memory.*
import com.notokens.monolog.ui.theme.PaperKeyText
import com.notokens.monolog.ui.theme.PaperKeySubText
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager

@Composable
internal fun MemorySettings() {
    val context = LocalContext.current
    val preferences = remember { MemoryPreferences(context) }
    var enabled by remember { mutableStateOf(preferences.enabled) }
    var confirmDisable by remember { mutableStateOf(false) }
    val encoder = remember { SentenceEncoder.get(context) }
    val status by encoder.status.collectAsState()
    SettingsCard("Journal memory") {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Remember meaningful moments", Modifier.weight(1f), color = PaperKeyText)
            Switch(enabled, { next ->
                if (next) { enabled = true; preferences.enabled = true }
                else confirmDisable = true
            })
        }
        Text("Monolog keeps compact records of meaningful text it helped you type. These are Drafted observations, even when you tap Send. Incognito and protected fields are excluded.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        Text("Notes, screenshots, and reminders you explicitly save are retained. Edit, pin, or delete their journal moments at any time.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
    }
    if (confirmDisable) AlertDialog(
        onDismissRequest = { confirmDisable = false },
        title = { Text("Turn off journal memory?") },
        text = { Text("Monolog will stop saving new moments from your typing. Existing moments and anything you save yourself will stay in your journal.") },
        confirmButton = { TextButton({ enabled = false; preferences.enabled = false; confirmDisable = false }) { Text("Turn off") } },
        dismissButton = { TextButton({ confirmDisable = false }) { Text("Keep on") } }
    )
    SettingsCard("Offline semantic search") {
        Text(status, color = PaperKeyText)
        Text("Multilingual MiniLM · 384-dimensional quantized vectors. The optional 135 MB download helps find related wording. Keyword search already covers titles, notes, and saved image tags.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        Button(encoder::download, enabled = !encoder.ready()) { Text("Download offline search") }
    }
}

@Composable
internal fun MemoryJournal(
    onOpenNote: (Long) -> Unit, onOpenReminder: (Long) -> Unit, onNewNote: () -> Unit,
    visibleNotes: List<com.notokens.monolog.data.Note>, photos: Map<Long, List<com.notokens.monolog.data.NotePhotoRef>>,
    query: String, onClearQuery: () -> Unit, onFocusSearch: () -> Unit,
    askRequest: Int, onAnswering: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val db = remember { MonologDatabase.get(context) }
    val repository = remember { MemoryRepository(context) }
    val search = remember { MemorySearch(context) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val encoder = remember { SentenceEncoder.get(context) }
    val encoderStatus by encoder.status.collectAsState()
    val semanticReady = encoder.ready()
    val records by db.memories().observeAll().collectAsState(initial = emptyList())
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var period by rememberSaveable { mutableStateOf("Day") }
    var showDay by rememberSaveable { mutableStateOf(false) }
    val showSearch = query.isNotBlank()
    val month = remember(date) { java.time.YearMonth.from(LocalDate.parse(date)) }
    var matches by remember { mutableStateOf<List<MemoryMatch>?>(null) }
    var brief by remember { mutableStateOf<DayBrief?>(null) }
    var answer by remember { mutableStateOf<JournalAnswer?>(null) }
    var editing by remember { mutableStateOf<MemoryRecord?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val selected = runCatching { LocalDate.parse(date) }.getOrNull()
    val year = selected?.year ?: LocalDate.now().year
    var answerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var handledAsk by remember { mutableIntStateOf(askRequest) }
    DisposableEffect(Unit) { onDispose { answerJob?.cancel() } }
    fun askJournal() {
        if (query.isBlank() || busy) return
        val asked = query
        val askedYear = year
        focus.clearFocus(force = true)
        keyboard?.hide()
        answerJob?.cancel()
        answerJob = scope.launch {
            busy = true
            onAnswering(true)
            error = ""
            try {
                val result = search.ask(asked, askedYear)
                if (query == asked && year == askedYear) {
                    answer = result
                    matches = result.matches
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Couldn't answer on this device. Matching journal entries are shown below." }
            finally { busy = false; onAnswering(false) }
        }
    }
    LaunchedEffect(askRequest) {
        if (askRequest != handledAsk) {
            handledAsk = askRequest
            askJournal()
        }
    }
    fun includesDay(day: LocalDate): Boolean = when (period) {
        "Year" -> day.year == year
        "Month" -> day.toString().startsWith(date.take(7))
        else -> day.toString() == date
    }
    val noteIds = remember(visibleNotes) { visibleNotes.mapTo(hashSetOf()) { it.id } }
    val selectedRecords = remember(records, noteIds, date, period) {
        records.filter { row ->
            (row.noteId == null || row.noteId in noteIds) &&
                LocalDate.parse(row.localDay).let(::includesDay)
        }
    }
    val selectedNotes = remember(visibleNotes, date, period) {
        val zone = java.time.ZoneId.systemDefault()
        visibleNotes.filter { note ->
            includesDay(java.time.Instant.ofEpochMilli(note.createdAt).atZone(zone).toLocalDate())
        }
    }
    val hasRecapSources = selectedRecords.isNotEmpty() || selectedNotes.isNotEmpty()
    val summarizer = remember { BriefModelStore.get(context) }
    val modelState by summarizer.state.collectAsState()
    val modelReady = modelState is com.notokens.monolog.ai.ModelState.Ready
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var refreshRecap by remember { mutableIntStateOf(0) }
    var lastRefresh by remember { mutableIntStateOf(0) }
    var buildingRecap by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { repository.backfill() } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Existing saved notes stay available in the journal. */ }
        MemoryIndexWork.enqueue(context)
    }
    LaunchedEffect(selectedRecords.map { it.id to it.revision }, selectedNotes.map { it.id to it.updatedAt },
        date, period, modelReady, refreshRecap, showDay) {
        brief = null
        if (!showDay) return@LaunchedEffect
        buildingRecap = hasRecapSources
        try {
            val refresh = refreshRecap != lastRefresh
            lastRefresh = refreshRecap
            search.brief(selectedRecords, selectedNotes, "$period:$date", refresh).collect { brief = it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            error = "The recap is unavailable. Recorded moments are shown below."
        } finally {
            buildingRecap = false
        }
    }
    LaunchedEffect(query, year, records.map { it.id to it.revision }) {
        answerJob?.cancel()
        answer = null
    }
    LaunchedEffect(query, year, semanticReady, records.map { Triple(it.id, it.revision, it.vectorRevision) }) {
        delay(250)
        try { matches = if (query.isBlank()) null else search.search(query, year) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { matches = emptyList(); error = "Search is unavailable. Your saved moments are kept." }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!showDay && !showSearch) {
            JournalTimeline(month, records.filter { it.noteId == null || it.noteId in noteIds }, visibleNotes, photos,
                onMonth = { date = it.atDay(1).toString() },
                onDay = { date = it.toString(); period = "Day"; showDay = true },
                onNote = onOpenNote,
                onSearch = onFocusSearch)
        }
        if (showDay || showSearch) {
        TextButton({ showDay = false; onClearQuery(); answerJob?.cancel() }) {
            Text("Back to timeline")
        }
        if (showDay) {

        Column(Modifier.fillMaxWidth().glassSurface(24).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (period) { "Day" -> "Your day in brief"; "Month" -> "Your month in brief"; else -> "$year in brief" },
                color = PaperKeyText, style = MaterialTheme.typography.titleLarge)
            Text(brief?.text ?: if (!hasRecapSources) "Save a thought or a meaningful draft to begin."
                else if (buildingRecap) "Preparing your recap…" else "Recap unavailable.",
                color = PaperKeyText)
            if (brief != null && hasRecapSources) {
                Text(if (brief?.generated == true) "On-device AI summary"
                    else if (buildingRecap && modelReady) "Preparing the AI summary…"
                    else "Journal overview",
                    color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
            }
            if (hasRecapSources && BriefModelStore.supported) {
                when (val state = modelState) {
                    com.notokens.monolog.ai.ModelState.Ready -> {
                        TextButton(enabled = !buildingRecap, onClick = { refreshRecap++ }) { Text("Refresh summary") }
                    }
                    is com.notokens.monolog.ai.ModelState.Downloading -> {
                        LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading summarizer · ${state.percent}%", color = PaperKeySubText,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    else -> {
                        if (state is com.notokens.monolog.ai.ModelState.Failed) {
                            Text(state.message, color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(summarizer::download) { Text("Download LiteRT summarizer (118 MB)") }
                        TextButton({ uriHandler.openUri("https://ai.google.dev/gemma/terms") }) { Text("Model terms") }
                    }
                }
            }

        }
        }
        Text(if (semanticReady) "On-device search · $year · answers use your matching entries"
            else "Keyword search · $year · $encoderStatus", color = PaperKeySubText,
            style = MaterialTheme.typography.labelSmall)
        answer?.let {
            Column(Modifier.fillMaxWidth().glassSurface(20).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("From your journal", color = PaperKeySubText, style = MaterialTheme.typography.labelMedium)
                Text(it.text, color = PaperKeyText)
                if (it.paraphrase) Text("On-device answer · see matching moments below", color = PaperKeySubText,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val current = LocalDate.parse(date)
                android.app.DatePickerDialog(context, { _, y, m, d ->
                    date = LocalDate.of(y, m + 1, d).toString()
                }, current.year, current.monthValue - 1, current.dayOfMonth).show()
            }) {
                MonologIcon(Glyph.CLOCK, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)))
            }
            Spacer(Modifier.weight(1f))
            IconControl(Glyph.PLUS, "Add note", onNewNote)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Day", "Month", "Year").forEach { value ->
                FilterChip(period == value, { period = value }, { Text(value) })
            }
        }
        val shown = (matches?.map { it.record } ?: selectedRecords)
            .filter { row -> row.noteId == null || visibleNotes.any { it.id == row.noteId } }
            .sortedByDescending { it.timestamp }
        var page by rememberSaveable(date, period, query) { mutableIntStateOf(0) }
        val lastPage = ((shown.size - 1).coerceAtLeast(0)) / 12
        val currentPage = page.coerceIn(0, lastPage)
        shown.drop(currentPage * 12).take(12).forEachIndexed { index, row ->
            val previous = shown.getOrNull(currentPage * 12 + index - 1)
            if (index == 0 || previous?.localDay != row.localDay) {
                val label = LocalDate.parse(row.localDay).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL))
                Text(label, color = PaperKeySubText, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            val evidence = matches?.firstOrNull { it.record.id == row.id }?.evidence
                ?: remember(row.id, row.revision) { MemoryRepository.decode(row) }
            Column(Modifier.fillMaxWidth().glassSurface(20).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${row.source} · ${row.localDay}" + if (row.pinned) " · Pinned" else "",
                    color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
                Text(MemoryRepository.readableSummary(evidence.summary), color = PaperKeyText, style = MaterialTheme.typography.bodyLarge)
                evidence.anger?.let { score ->
                    Text("A$score · ${evidence.angerOrigin}. ${evidence.mood.orEmpty()}",
                        color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    row.noteId?.let { id -> TextButton({ onOpenNote(id) }) { Text("Open saved note") } }
                    row.reminderId?.let { id -> TextButton({ onOpenReminder(id) }) { Text("Open reminder") } }
                    TextButton({ editing = row }) { Text("Edit") }
                    TextButton({ scope.launch { repository.edit(row, evidence.summary, evidence.anger, !row.pinned) } }) {
                        Text(if (row.pinned) "Unpin" else "Pin")
                    }
                    TextButton({ scope.launch { repository.delete(row.id) } }) { Text("Delete") }
                }
            }
        }
        if (lastPage > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton({ page = currentPage - 1 }, enabled = currentPage > 0) { Text("Previous") }
            Text("${currentPage + 1} / ${lastPage + 1}", color = PaperKeySubText)
            TextButton({ page = currentPage + 1 }, enabled = currentPage < lastPage) { Text("Next") }
        }
        if (shown.isEmpty() && query.isNotBlank()) Text("No matching recorded moments in $year.", color = PaperKeySubText)
        if (shown.isEmpty() && query.isBlank()) Text("No moments in this period.", color = PaperKeySubText)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
    editing?.let { row ->
        val evidence = remember(row.id, row.revision) { MemoryRepository.decode(row) }
        var summary by remember(row.id) { mutableStateOf(evidence.summary) }
        var anger by remember(row.id) { mutableStateOf(evidence.anger?.toString().orEmpty()) }
        val score = anger.toIntOrNull()
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit moment") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(summary, { summary = it }, label = { Text("What happened?") })
                OutlinedTextField(anger, { anger = it.filter(Char::isDigit).take(2) }, label = { Text("Anger score · 1–10, optional") },
                    isError = anger.isNotBlank() && (score == null || score !in 1..10))
                Text("An observation about this moment. Your edit is marked self-reported.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { Button(enabled = summary.isNotBlank() && (anger.isBlank() || (score != null && score in 1..10)), onClick = {
                scope.launch { repository.edit(row, summary, score, row.pinned); editing = null }
            }) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
}
