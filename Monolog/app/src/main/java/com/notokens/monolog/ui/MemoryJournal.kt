package com.notokens.monolog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.notokens.monolog.data.MemoryRecord
import com.notokens.monolog.data.MonologDatabase
import com.notokens.monolog.ai.ModelState
import com.notokens.monolog.ai.ModelStore
import com.notokens.monolog.ai.TextModel
import com.notokens.monolog.data.Note
import com.notokens.monolog.memory.*
import com.notokens.monolog.ui.theme.PaperKeyText
import com.notokens.monolog.ui.theme.PaperKeySubText
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
internal fun JournalMemoryPrompt() {
    val context = LocalContext.current
    val preferences = remember(context) { MemoryPreferences(context) }
    val enabled by preferences.enabledChanges.collectAsState(initial = preferences.enabled)
    if (enabled) return
    SettingsCard("Turn on Journal memory") {
        Text("Save meaningful moments from what you type, including unsent drafts. Passwords, protected fields and incognito typing are excluded.",
            color = PaperKeySubText, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { preferences.enabled = true }) { Text("Turn on Journal memory") }
    }
}

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
    askRequest: Int, onAnswering: (Boolean) -> Unit, onDayOpened: () -> Unit,
    initialDay: String? = null, notesLoaded: Boolean = true
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val db = remember { MonologDatabase.get(context) }
    val repository = remember { MemoryRepository(context) }
    val search = remember { MemorySearch(context) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val encoder = remember { SentenceEncoder.get(context) }
    val encoderStatus by encoder.status.collectAsState()
    val semanticReady = encoder.ready()
    val loadedMoments by db.memories().observeAll().collectAsState<List<MemoryRecord>, List<MemoryRecord>?>(initial = null)
    val records = loadedMoments.orEmpty()
    var date by rememberSaveable(initialDay) { mutableStateOf(initialDay ?: LocalDate.now().toString()) }
    var calendarMonth by rememberSaveable(initialDay) { mutableStateOf(YearMonth.from(LocalDate.parse(date)).toString()) }
    var period by rememberSaveable { mutableStateOf("Day") }
    var showDay by rememberSaveable(initialDay) { mutableStateOf(initialDay != null) }
    val showSearch = query.isNotBlank()
    val month = remember(calendarMonth) { YearMonth.parse(calendarMonth) }
    val timelineState = rememberSaveableStateHolder()
    val selected = remember(date) { LocalDate.parse(date) }
    val year = if (showDay) selected.year else month.year
    val recapPeriod = if (showDay) period else "Day"
    val showBrief = showDay || !showSearch
    val textModels = remember { ModelStore.get(context) }
    val activeModel by textModels.active.collectAsState()
    val textModelState by textModels.state.collectAsState()
    val preferredModel = activeModel.takeIf { textModelState == ModelState.Ready }
    var matches by remember { mutableStateOf<List<MemoryMatch>?>(null) }
    var answer by remember { mutableStateOf<JournalAnswer?>(null) }
    var editing by remember { mutableStateOf<MemoryRecord?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
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
    fun includesDay(day: LocalDate): Boolean = when (recapPeriod) {
        "Year" -> day.year == selected.year
        "Month" -> YearMonth.from(day) == YearMonth.from(selected)
        else -> day == selected
    }
    val noteIds = remember(visibleNotes) { visibleNotes.mapTo(hashSetOf()) { it.id } }
    val selectedRecords = remember(records, noteIds, selected, recapPeriod) {
        records.filter { row ->
            (row.noteId == null || row.noteId in noteIds) &&
                LocalDate.parse(row.localDay).let(::includesDay)
        }
    }
    val selectedNotes = remember(visibleNotes, selected, recapPeriod) {
        val zone = java.time.ZoneId.systemDefault()
        visibleNotes.filter { note ->
            includesDay(java.time.Instant.ofEpochMilli(note.createdAt).atZone(zone).toLocalDate())
        }
    }
    val hasRecapSources = selectedRecords.isNotEmpty() || selectedNotes.isNotEmpty()
    val summarizer = remember { BriefModelStore.get(context) }
    val modelState by summarizer.state.collectAsState()
    val backupReady = modelState == ModelState.Ready
    val periodKey = "$recapPeriod:" + when (recapPeriod) {
        "Month" -> YearMonth.from(selected).toString()
        "Year" -> selected.year.toString()
        else -> date
    }
    var refreshRecap by remember(periodKey) { mutableIntStateOf(0) }
    var lastRefresh by remember(periodKey) { mutableIntStateOf(0) }
    var briefUpdate by remember(periodKey) { mutableStateOf(BriefUpdate()) }
    val observationsReady = notesLoaded && loadedMoments != null
    val briefInputs by rememberUpdatedState(
        BriefInputs(selectedRecords, selectedNotes, preferredModel, backupReady, refreshRecap)
    )
    LaunchedEffect(Unit) {
        try { repository.backfill() } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Existing saved notes stay available in the journal. */ }
        MemoryIndexWork.enqueue(context)
    }
    LaunchedEffect(periodKey, showBrief, observationsReady, lifecycle) {
        if (!showBrief || !observationsReady) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { briefInputs.refreshKey }.collect {
                val input = briefInputs
                try {
                    search.brief(input.records, input.notes, periodKey, input.model, input.backupReady,
                        refresh = input.request != lastRefresh).collect { briefUpdate = it }
                    lastRefresh = input.request
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    briefUpdate = briefUpdate.copy(message = "Couldn’t open or refresh the brief.")
                } finally {
                    briefUpdate = briefUpdate.copy(refreshing = false)
                }
            }
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
        if (showBrief) {
            JournalBriefCard(
                date = selected,
                period = recapPeriod,
                brief = briefUpdate.brief,
                hasSources = hasRecapSources,
                sourcesReady = observationsReady,
                building = briefUpdate.refreshing,
                message = briefUpdate.message,
                canRefresh = preferredModel != null || backupReady,
                backupState = modelState,
                onRefresh = { refreshRecap++ },
                onDownload = summarizer::download
            )
        }
        JournalMemoryPrompt()
        if (!showDay && !showSearch) {
            timelineState.SaveableStateProvider("timeline") {
                JournalTimeline(month, records.filter { it.noteId == null || it.noteId in noteIds }, visibleNotes, photos,
                    onMonth = { calendarMonth = it.toString() },
                    onDay = {
                        date = it.toString()
                        calendarMonth = YearMonth.from(it).toString()
                        period = "Day"
                        showDay = true
                        onDayOpened()
                    },
                    onNote = onOpenNote,
                    onSearch = onFocusSearch)
            }
        }
        if (showDay || showSearch) {
        TextButton({ showDay = false; onClearQuery(); answerJob?.cancel() }) {
            Text("Back to timeline")
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
                    val day = LocalDate.of(y, m + 1, d)
                    date = day.toString()
                    calendarMonth = YearMonth.from(day).toString()
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

private data class BriefInputs(
    val records: List<MemoryRecord>, val notes: List<Note>, val model: TextModel?,
    val backupReady: Boolean, val request: Int
) {
    val refreshKey get() = listOf(records.map { it.id to it.revision },
        notes.map { it.id to it.updatedAt }, model, backupReady, request)
}

@Composable
private fun JournalBriefCard(
    date: LocalDate,
    period: String,
    brief: DayBrief?,
    hasSources: Boolean,
    sourcesReady: Boolean,
    building: Boolean,
    message: String?,
    canRefresh: Boolean,
    backupState: ModelState,
    onRefresh: () -> Unit,
    onDownload: () -> Unit
) {
    val dateLabel = when (period) {
        "Month" -> date.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        "Year" -> date.year.toString()
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    }
    Column(
        Modifier.fillMaxWidth().glassSurface(24, GlassAccent.LAVENDER).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).glassSurface(12), contentAlignment = Alignment.Center) {
                MonologIcon(Glyph.BOOK, Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (period) { "Month" -> "Your month in brief"; "Year" -> "Your year in brief"; else -> "Your day in brief" },
                    color = PaperKeyText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
                )
                Text(dateLabel, color = PaperKeySubText, style = MaterialTheme.typography.labelMedium)
            }
            if (sourcesReady && hasSources && canRefresh) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !building,
                    modifier = Modifier.align(Alignment.Top).semantics {
                        contentDescription = if (building) "Refreshing summary" else "Refresh summary"
                    }
                ) {
                    if (building) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else MonologIcon(Glyph.REFRESH, Modifier.size(20.dp))
                }
            }
        }
        if (building) RefreshingBriefLabel()
        val description = brief?.text ?: when {
            !sourcesReady -> "Opening your brief…"
            !hasSources -> "No journal moments saved for this period yet."
            building -> null
            else -> "Preparing your brief…"
        }
        if (description != null) Text(description, color = if (hasSources) PaperKeyText else PaperKeySubText,
            style = MaterialTheme.typography.bodyMedium)
        message?.takeUnless { building }?.let {
            Text(it, color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
        }
        if (hasSources) {
            if (!building && brief?.generated == false) {
                Text("Journal overview", color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
            }
            when {
                backupState is ModelState.Downloading -> {
                    LinearProgressIndicator(progress = { backupState.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading backup summarizer · ${backupState.percent}%", color = PaperKeySubText,
                        style = MaterialTheme.typography.labelSmall)
                }
                BriefModelStore.supported && backupState != ModelState.Ready && brief?.generated != true && !building -> {
                    if (backupState is ModelState.Failed) {
                        Text(backupState.message, color = PaperKeySubText, style = MaterialTheme.typography.labelSmall)
                    }
                    GemmaDownloadButton("Download backup summarizer (118 MB)", download = onDownload)
                }
            }
        }
    }
}

@Composable
private fun RefreshingBriefLabel() {
    val phase by rememberInfiniteTransition(label = "Brief refresh").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1450, easing = LinearEasing)),
        label = "Glow across refresh text"
    )
    var width by remember { mutableIntStateOf(1) }
    val base = PaperKeySubText
    val highlight = lerp(MaterialTheme.colorScheme.primary, PaperKeyText, .34f)
    val center = (phase * 1.5f - .25f) * width
    val span = width * .24f
    val glow = Brush.horizontalGradient(listOf(base, highlight, base), startX = center - span, endX = center + span)
    BasicText("Refreshing Brief…",
        modifier = Modifier.onSizeChanged { width = it.width.coerceAtLeast(1) }
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.labelMedium.merge(TextStyle(brush = glow)))
}
