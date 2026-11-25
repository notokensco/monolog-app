package com.notokens.monolog.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.notokens.monolog.R
import com.notokens.monolog.ai.RewritePipeline
import com.notokens.monolog.ime.ui.KeyboardThemePreview
import androidx.compose.ui.layout.ContentScale
import com.notokens.monolog.capture.captureToJournal
import com.notokens.monolog.capture.TagMetadataCodec
import com.notokens.monolog.ime.vox.VoiceNoteBody
import com.notokens.monolog.memory.JournalAnswer
import com.notokens.monolog.memory.MemorySearch
import com.notokens.monolog.capture.copyCompanionWindow
import com.notokens.monolog.data.*
import com.notokens.monolog.setup.SetupScreen
import com.notokens.monolog.ui.theme.*
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val ink: Color @Composable get() = PaperKeyText
private val muted: Color @Composable get() = PaperKeySubText
private val todayMemoryRequest = Regex(
    """\b(?:my (?:journal|notes?|memories|photos?|screenshots?|day)|(?:what|when|where) did (?:i|we)|saved (?:notes?|memories|photos?))\b""",
    RegexOption.IGNORE_CASE
)
private val LocalSettingsAction = compositionLocalOf<(SettingsPage?) -> Unit> { error("Settings navigation is missing") }

fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun pickReminderTime(context: Context, initial: Long, inkMode: Boolean, selected: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply { timeInMillis = initial }
    val theme = if (inkMode) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    DatePickerDialog(context, theme, { _, year, month, day ->
        TimePickerDialog(context, theme, { _, hour, minute ->
            val result = Calendar.getInstance().apply {
                set(year, month, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }
            selected(result.timeInMillis)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
}

enum class CompanionDestination(val route: String) {
    NEW_NOTE("note/new"), NEW_REMINDER("reminder/new"),
    TODAY("today"), JOURNAL("journal"), LIBRARY("library"), SETTINGS("settings"), VOICE("settings/voice"), APPEARANCE("settings/appearance")
}

@Composable
fun CompanionApp(
    settings: SettingsStore,
    appearance: Appearance,
    destination: CompanionDestination?,
    personalRoute: String? = null,
    onDestinationOpened: () -> Unit,
    onRequestMicPermission: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { MonologData.repository(context) }
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    val firstRunPrefs = remember { context.getSharedPreferences("first_run_setup", Context.MODE_PRIVATE) }
    val startRoute = remember {
        if (firstRunPrefs.getBoolean("completed", false)) CompanionDestination.TODAY.route else "onboarding"
    }
    val finishSetup: (String) -> Unit = { route ->
        firstRunPrefs.edit().putBoolean("completed", true).apply()
        nav.navigate(route) { popUpTo("onboarding") { inclusive = true }; launchSingleTop = true }
    }
    val settingsScroll = rememberScrollState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route?.substringBefore('?') ?: CompanionDestination.TODAY.route
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val navigateBack: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        nav.popBackStack()
    }
    val openSettings: (SettingsPage?) -> Unit = { page ->
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        nav.navigate(page?.route ?: CompanionDestination.SETTINGS.route) { launchSingleTop = true }
        if (page == null) scope.launch { settingsScroll.scrollTo(0) }
    }
    LaunchedEffect(destination, personalRoute) {
        if (personalRoute != null) {
            if (startRoute != "onboarding" || firstRunPrefs.getBoolean("completed", false)) {
                nav.navigate(personalRoute) { launchSingleTop = true }
            }
            onDestinationOpened()
        } else if (destination != null) {
            when (destination) {
                CompanionDestination.SETTINGS -> openSettings(null)
                else -> nav.navigate(destination.route) {
                    popUpTo(CompanionDestination.TODAY.route)
                    launchSingleTop = true
                }
            }
            onDestinationOpened()
        }
    }
    var confirmClear by remember { mutableStateOf(false) }
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val imeVisible = imeInsets.getBottom(density) > 0
    val view = LocalView.current

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear local data?") },
        text = { Text("This deletes journal entries, saved context, attachments, reminders, keyboard learning, usage totals, clipboard history, and recent searches from this device. Drive backups can be deleted separately in Account & backup.") },
        confirmButton = { Button(onClick = {
            confirmClear = false
            scope.launch {
                com.notokens.monolog.ime.DraftRescue.clear()
                com.notokens.monolog.ime.ChatAssist.stop()
                context.stopService(android.content.Intent(context, com.notokens.monolog.ime.ChatAssistService::class.java))
                com.notokens.monolog.connect.LocalConnect.stop()
                com.notokens.monolog.connect.DriveConnect(context.applicationContext).discardLocalSession()
                com.notokens.monolog.ime.ui.clearRecentKeyboardClips()
                settings.resetLocalContent()
                com.notokens.monolog.ime.ui.clearClipboardImageStorage(context.applicationContext)
                repo.clearAllData()
            }
        }) { Text("Clear data") } },
        dismissButton = { OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )

    CompositionLocalProvider(LocalSettingsAction provides openSettings) {
        Box(Modifier.fillMaxSize().monologBackdrop()) {
            Scaffold(containerColor = Color.Transparent, modifier = Modifier.imePadding(), bottomBar = {
                if (!imeVisible && route in listOf("today", "journal", "library")) {
                    Box(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            Modifier.widthIn(max = 608.dp).fillMaxWidth().glassSurface(28).padding(4.dp).selectableGroup()
                        ) {
                            listOf(Triple("Today", "today", Glyph.HOME), Triple("Journal", "journal", Glyph.BOOK), Triple("Library", "library", Glyph.LAYERS)).forEach { (label, tab, icon) ->
                                val selected = route == tab
                                val itemColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else muted
                                Column(
                                    Modifier.weight(1f)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(if (selected) PaperKeyActive else Color.Transparent)
                                        .selectable(selected, role = Role.Tab) {
                                            nav.navigate(tab) { launchSingleTop = true; popUpTo("today") }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    MonologIcon(icon, Modifier.size(20.dp), itemColor)
                                    Spacer(Modifier.height(4.dp))
                                    Text(label, color = itemColor, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }) { padding ->
                NavHost(nav, startDestination = startRoute, modifier = Modifier.padding(padding)) {
                    composable("onboarding") {
                        FirstRunSetup(settings, onRequestMicPermission,
                            onFinish = { finishSetup("today") },
                            onModels = { finishSetup(SettingsPage.MODELS.route) },
                            onMemory = { finishSetup(SettingsPage.MEMORY.route) })
                    }
                    composable("today") {
                        var query by rememberSaveable { mutableStateOf("") }
                        var answer by remember { mutableStateOf<String?>(null) }
                        var answering by remember { mutableStateOf(false) }
                        var request by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                        val searchField = remember { FocusRequester() }
                        val todayScope = rememberCoroutineScope()
                        val todayScroll = rememberScrollState()
                        val journal = remember(context) { MemorySearch(context.applicationContext) }
                        Screen("Your day, remembered.", showWeatherAndDate = true, scrollState = todayScroll, footer = {
                            JournalAskBar(query, {
                                request?.cancel()
                                query = it
                                answer = null
                            }, "Ask about your days\u2026", searchField, answering,
                                { nav.navigate("note/new") }, {
                                    if (query.isNotBlank() && !answering) {
                                        val asked = query
                                        focusManager.clearFocus(force = true)
                                        keyboard?.hide()
                                        request = todayScope.launch {
                                            answering = true
                                            answer = null
                                            try {
                                                val reply = if (todayMemoryRequest.containsMatchIn(asked)) {
                                                    journal.ask(asked).text
                                                } else RewritePipeline.respondOffline(context.applicationContext, asked).text
                                                if (query == asked) {
                                                    answer = reply
                                                    todayScroll.scrollTo(0)
                                                }
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) {
                                                if (query == asked) answer = "The on-device answer is unavailable. Try again."
                                            } finally { answering = false }
                                        }
                                    }
                                })
                        }) {
                            JournalMemoryPrompt()
                            answer?.let { reply ->
                                Column(Modifier.fillMaxWidth().glassSurface(24).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("On-device answer", color = muted, style = MaterialTheme.typography.labelMedium)
                                    Text(reply, color = ink, style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            val tileHeight = ((LocalConfiguration.current.screenHeightDp - 512) / 2).coerceIn(124, 152).dp
                            val zone = ZoneId.systemDefault()
                            val today = LocalDate.now(zone)
                            val allNotes by repo.notes.collectAsState(initial = emptyList())
                            val visibleNotes = visibleBackupYears(allNotes)
                            val todayNote = visibleNotes.firstOrNull {
                                it.updatedAt >= today.atStartOfDay(zone).toInstant().toEpochMilli() &&
                                    it.updatedAt < today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                            }
                            val latestNote = visibleNotes.firstOrNull()
                            var reminderCutoff by remember { mutableLongStateOf(System.currentTimeMillis()) }
                            val next by remember(reminderCutoff) { repo.nextReminder(reminderCutoff) }.collectAsState(initial = null)
                            LaunchedEffect(next?.dueAt) {
                                next?.let {
                                    delay((it.dueAt - System.currentTimeMillis()).coerceAtLeast(1))
                                    reminderCutoff = maxOf(System.currentTimeMillis(), it.dueAt)
                                }
                            }
                            Column(
                                Modifier.fillMaxWidth().glassSurface(24, GlassAccent.BLUE).journalPageAccent()
                                    .clickable { nav.navigate(todayNote?.let { "note/edit/${it.id}" } ?: "note/new") }.padding(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconBadge(Glyph.BOOK)
                                    Text("Today’s journal", color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.height(8.dp))
                                val excerpt = remember(todayNote) { todayNote?.let { markdownExcerpt(TagMetadataCodec.decode(it.body).cleanBody).take(130).ifBlank { it.title } } }
                                Text(excerpt ?: "A little space for your thoughts, conversations, and progress.", color = muted, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Continue writing", color = PaperAccentBlue, style = MaterialTheme.typography.labelLarge)
                                    MonologIcon(Glyph.ARROW, Modifier.size(14.dp), PaperAccentBlue)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DashboardTile("Reminders", next?.title ?: "Nothing coming up", next?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.dueAt)) } ?: "Add a reminder", Glyph.CLOCK, GlassAccent.BLUE, Modifier.weight(1f).heightIn(min = tileHeight)) { nav.navigate("reminders") }
                                DashboardTile("Recent memory", latestNote?.title ?: "Your story starts here", "Open Journal", Glyph.BOOK, GlassAccent.LAVENDER, Modifier.weight(1f).heightIn(min = tileHeight)) { nav.navigate("journal") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DashboardTile("Context library", "Photos and notes", "View all", Glyph.LAYERS, GlassAccent.LAVENDER, Modifier.weight(1f).heightIn(min = tileHeight)) { nav.navigate("library") }
                                DashboardTile("Add a note", "Make this space yours", "Write something", Glyph.PLUS, GlassAccent.BLUSH, Modifier.weight(1f).heightIn(min = tileHeight)) { nav.navigate("note/new") }
                            }
                        }
                    }
                    composable("journal?ask={ask}&day={day}", arguments = listOf(
                        navArgument("ask") { type = NavType.BoolType; defaultValue = false },
                        navArgument("day") { type = NavType.StringType; nullable = true; defaultValue = null }
                    )) { journalEntry ->
                        val allNotes by repo.notes.collectAsState<List<Note>, List<Note>?>(initial = null)
                        val journalScroll = rememberLazyListState()
                        val journalScope = rememberCoroutineScope()
                        val notes = visibleBackupYears(allNotes.orEmpty())
                        val photoRefs by repo.notePhotos.collectAsState(initial = emptyList())
                        val photosByNote = remember(photoRefs) { photoRefs.groupBy { it.noteId } }
                        var query by rememberSaveable { mutableStateOf("") }
                        var askRequest by remember { mutableIntStateOf(0) }
                        var answering by remember { mutableStateOf(false) }
                        val searchField = remember { FocusRequester() }
                        LaunchedEffect(journalEntry) {
                            if (journalEntry.arguments?.getBoolean("ask") == true) {
                                withFrameNanos { }
                                searchField.requestFocus()
                            }
                        }
                        CollectionScreen("Journal", showWeatherAndDate = true, scrollState = journalScroll, footer = {
                            JournalAskBar(query, { query = it }, "Ask about your days\u2026", searchField,
                                answering, { nav.navigate("note/new") }, { askRequest++ })
                        }) {
                            item(contentType = "memory") {
                                MemoryJournal(
                                    onOpenNote = { nav.navigate("note/$it") },
                                    onOpenReminder = { nav.navigate("reminder/edit/$it") },
                                    onNewNote = { nav.navigate("note/new") },
                                    visibleNotes = notes, photos = photosByNote, notesLoaded = allNotes != null,
                                    query = query, onClearQuery = { query = "" },
                                    onFocusSearch = { searchField.requestFocus(); keyboard?.show() },
                                    askRequest = askRequest, onAnswering = { answering = it },
                                    onDayOpened = { journalScope.launch { journalScroll.scrollToItem(0) } },
                                    initialDay = journalEntry.arguments?.getString("day")
                                )
                            }
                            item { ArchivedYearsLink { nav.navigate(SettingsPage.ACCOUNT.route) } }
                        }
                    }
                    composable("library") {
                        var filter by rememberSaveable { mutableStateOf("All") }
                        var query by rememberSaveable { mutableStateOf("") }
                        var compact by rememberSaveable { mutableStateOf(false) }
                        var sort by rememberSaveable { mutableStateOf(LibrarySort.NEWEST) }
                        var selectedIds by rememberSaveable { mutableStateOf(longArrayOf()) }
                        var confirmDelete by rememberSaveable { mutableStateOf(false) }
                        var deleting by remember { mutableStateOf(false) }
                        var deleteError by remember { mutableStateOf(false) }
                        var creating by remember { mutableStateOf(false) }
                        var capturePending by remember { mutableStateOf(false) }
                        val screenScope = rememberCoroutineScope()
                        val librarySearch = remember(context) { MemorySearch(context.applicationContext) }
                        val searchField = remember { FocusRequester() }
                        var answering by remember { mutableStateOf(false) }
                        var answer by remember { mutableStateOf<JournalAnswer?>(null) }
                        var answerError by remember { mutableStateOf(false) }
                        var answerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                        var relatedIds by remember { mutableStateOf(emptySet<Long>()) }
                        LaunchedEffect(query) {
                            answerJob?.cancel()
                            answer = null
                            answerError = false
                            relatedIds = emptySet()
                            if (query.isNotBlank()) {
                                delay(240)
                                try {
                                    relatedIds = librarySearch.search(query).mapNotNull { it.record.noteId }.toSet()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* Keep matching titles and image tags available. */ }
                            }
                        }
                        val allNotes by repo.notes.collectAsState(initial = emptyList())
                        val notes = visibleBackupYears(allNotes)
                        val photoRefs by repo.notePhotos.collectAsState(initial = emptyList())
                        val photosByNote = remember(photoRefs) { photoRefs.groupBy { it.noteId } }
                        val entries = remember(notes, photosByNote, filter, query, relatedIds, sort) {
                            val candidates = notes.filter { note ->
                                val metadata = TagMetadataCodec.decode(note.body)
                                when (filter) {
                                    "Photos" -> note.id in photosByNote
                                    "Screenshots" -> metadata.tags.isNotEmpty() || note.body.contains("<!-- MONOLOG_CAPTURE:")
                                    "Voice" -> metadata.bottomIsVoice || metadata.tags.any { it.isVoice } || VoiceNoteBody.audio(note.body) != null
                                    else -> true
                                }
                            }
                            val matches = if (query.isBlank()) candidates else {
                                (candidates.matchingJournalEntries(query) + candidates.filter { it.id in relatedIds }).distinctBy { it.id }
                            }
                            matches.sortedWith(sort.comparator)
                        }
                        val selecting = selectedIds.isNotEmpty()
                        val selectedNotes = remember(notes, selectedIds) { notes.filter { it.id in selectedIds } }
                        fun toggleSelection(id: Long) {
                            if (deleting) return
                            if (!selecting) {
                                focusManager.clearFocus(force = true)
                                keyboard?.hide()
                            }
                            deleteError = false
                            selectedIds = if (id in selectedIds) selectedIds.filterNot { it == id }.toLongArray() else selectedIds + id
                        }
                        BackHandler(enabled = selecting) {
                            if (!deleting) {
                                selectedIds = longArrayOf()
                                deleteError = false
                            }
                        }
                        if (confirmDelete && selectedNotes.isNotEmpty()) {
                            val count = selectedNotes.size
                            AlertDialog(
                                onDismissRequest = { confirmDelete = false },
                                title = { Text(if (count == 1) "Delete this item?" else "Delete $count items?") },
                                text = { Text("This removes the selected items and their attachments from this device.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        confirmDelete = false
                                        deleting = true
                                        screenScope.launch {
                                            try {
                                                repo.deleteNotes(selectedNotes)
                                                selectedIds = longArrayOf()
                                                answerJob?.cancel()
                                                answer = null
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                deleteError = true
                                            } finally {
                                                deleting = false
                                            }
                                        }
                                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                                },
                                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
                            )
                        }
                        ScreenFrame(null, showWeatherAndDate = true) {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                item {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Context library", Modifier.weight(1f), color = ink,
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.SemiBold)
                                            LibraryViewOptions(compact, { compact = !compact }, sort, { sort = it }, enabled = !selecting)
                                            Box {
                                                IconControl(Glyph.PLUS, "Create", { creating = true }, frosted = true)
                                                DropdownMenu(
                                                    expanded = creating,
                                                    onDismissRequest = { creating = false },
                                                    shape = RoundedCornerShape(24.dp),
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    border = BorderStroke(1.dp, PaperPillBorder)
                                                ) {
                                                    DropdownMenuItem(text = { Text("Note or photo") },
                                                        leadingIcon = { MonologIcon(Glyph.PHOTO) },
                                                        onClick = { creating = false; nav.navigate("note/new") })
                                                    DropdownMenuItem(text = { Text("Journal entry") },
                                                        leadingIcon = { MonologIcon(Glyph.BOOK) },
                                                        onClick = { creating = false; nav.navigate("journal") })
                                                    DropdownMenuItem(text = { Text("Reminder") },
                                                        leadingIcon = { MonologIcon(Glyph.BELL) },
                                                        onClick = { creating = false; nav.navigate("reminders") })
                                                    DropdownMenuItem(text = { Text("Capture screen") },
                                                        leadingIcon = { MonologIcon(Glyph.CAPTURE) }, enabled = !capturePending,
                                                        onClick = {
                                                            creating = false
                                                            if (!capturePending) screenScope.launch {
                                                                capturePending = true
                                                                try {
                                                                    focusManager.clearFocus(force = true)
                                                                    keyboard?.hide()
                                                                    val ready = withTimeoutOrNull(1_500) {
                                                                        snapshotFlow { imeInsets.getBottom(density) == 0 }.first { it }
                                                                    } == true
                                                                    if (ready && view.hasWindowFocus()) {
                                                                        captureToJournal(context, settings) { copyCompanionWindow(view) }
                                                                    }
                                                                } finally {
                                                                    capturePending = false
                                                                }
                                                            }
                                                        })
                                                }
                                            }
                                        }
                                        Text("Saved moments, ready when you need them", color = muted,
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                answer?.let { result ->
                                    item {
                                        Column(Modifier.fillMaxWidth().glassSurface(20).padding(14.dp)) {
                                            Text("From your memories", style = MaterialTheme.typography.labelMedium, color = muted)
                                            Text(result.text, color = ink)
                                        }
                                    }
                                }
                                if (answerError) item { Text("The answer is unavailable. Search results are shown below.", color = muted) }
                                item {
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        listOf("All" to null, "Photos" to Glyph.PHOTO, "Screenshots" to Glyph.CAPTURE, "Voice" to Glyph.MIC).forEach { (label, glyph) ->
                                            FilterChip(filter == label, { filter = label }, { Text(label) }, enabled = !selecting,
                                                shape = RoundedCornerShape(22.dp),
                                                leadingIcon = glyph?.let { icon -> { MonologIcon(icon, Modifier.size(14.dp)) } })
                                        }
                                    }
                                }
                                if (entries.isEmpty()) item {
                                    EmptyCard(if (query.isNotBlank()) "No matching memories" else "Your memories start here",
                                        if (query.isNotBlank()) "Try another search or filter." else "Tap + to add a note or photo. Use M on the keyboard to capture a screen.")
                                }
                                item { ArchivedYearsLink { nav.navigate(SettingsPage.ACCOUNT.route) } }
                                items(entries.chunked(if (compact) 1 else 2), key = { row -> row.first().id }) { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        row.forEach { note ->
                                            Box(Modifier.weight(1f)) {
                                                NoteTile(
                                                    note, photosByNote[note.id].orEmpty(), compact, selecting, note.id in selectedIds,
                                                    onSelect = { toggleSelection(note.id) },
                                                    onClick = {
                                                        if (selecting) toggleSelection(note.id) else nav.navigate("note/${note.id}")
                                                    }
                                                )
                                            }
                                        }
                                        if (!compact && row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                                item {
                                    Row(Modifier.fillMaxWidth().glassSurface(24).clickable { nav.navigate("journal") }.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        MonologIcon(Glyph.BOOK)
                                        Text("Open Journal", Modifier.weight(1f), color = ink)
                                        MonologIcon(Glyph.ARROW)
                                    }
                                }
                            }
                            if (selecting) {
                                if (deleteError) Text("Couldn’t delete the selected items. Try again.",
                                    color = MaterialTheme.colorScheme.error)
                                Row(
                                    Modifier.fillMaxWidth().glassSurface(24).padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (deleting) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                                    else IconControl(Glyph.CLOSE, "Clear selection", { selectedIds = longArrayOf(); deleteError = false })
                                    Text(
                                        "${selectedNotes.size} selected",
                                        Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                        color = ink
                                    )
                                    TextButton(onClick = { confirmDelete = true }, enabled = !deleting && selectedNotes.isNotEmpty(),
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                        Text("Delete")
                                    }
                                }
                            } else JournalAskBar(query, { query = it }, "Ask about your memories\u2026", searchField,
                                answering, { nav.navigate("note/new") }, {
                                    if (query.isNotBlank() && !answering) {
                                        val asked = query
                                        focusManager.clearFocus(force = true)
                                        keyboard?.hide()
                                        answerJob = screenScope.launch {
                                            answering = true
                                            answerError = false
                                            try {
                                                val result = librarySearch.ask(asked)
                                                if (query == asked) answer = result
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { answerError = true }
                                            finally { answering = false }
                                        }
                                    }
                                })
                        }
                    }
                    composable("note/new") { NoteEditor(null, emptyList(), onCancel = navigateBack) }
                    composable("note/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        val id = entry.arguments!!.getLong("id")
                        val note by remember(id) { repo.note(id) }.collectAsState(initial = null)
                        val photos by remember(id) { repo.notePhotos(id) }.collectAsState(initial = emptyList())
                        var activeViewerPhotoId by remember { mutableStateOf<Long?>(null) }
                        note?.let { note ->
                            Screen(note.title, onBack = navigateBack) {
                                val decoded = remember(note.body) { TagMetadataCodec.decode(note.body) }
                                Text(formatTime(note.updatedAt), color = muted)
                                if (photos.isNotEmpty()) {
                                    Spacer(Modifier.height(14.dp))
                                    // Primary photo interactive hero card
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 200.dp, max = 340.dp)
                                            .clip(RoundedCornerShape(22.dp))
                                            .border(2.dp, PaperAccentBlue.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                                            .clickable { activeViewerPhotoId = photos.first().id }
                                    ) {
                                        JournalPhotoImage(
                                            JournalPhoto.Saved(photos.first().id),
                                            "Capture photo",
                                            Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 340.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color.Black.copy(alpha = 0.72f),
                                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                MonologIcon(Glyph.EXPAND, Modifier.size(14.dp), Color.White)
                                                Text(
                                                    when (decoded.tags.size) { 0 -> "Open screenshot"; 1 -> "Open pinned note"; else -> "Open ${decoded.tags.size} pinned notes" },
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                    if (photos.size > 1) {
                                        Spacer(Modifier.height(10.dp))
                                        JournalPhotoStrip(photos.drop(1).map { JournalPhoto.Saved(it.id) })
                                    }
                                }
                                if (decoded.hasMetadata) {
                                    Spacer(Modifier.height(14.dp))
                                    CaptureDetails(decoded)
                                } else if (decoded.cleanBody.isNotBlank()) {
                                    Spacer(Modifier.height(14.dp))
                                    JournalMarkdown(decoded.cleanBody, Modifier.fillMaxWidth().glassSurface().padding(16.dp))
                                }
                                VoiceNoteBody.audio(note.body)?.let { VoiceNotePlayback(it) }
                                Spacer(Modifier.height(20.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { nav.navigate("note/edit/${note.id}") }) { Text("Edit") }
                                    OutlinedButton(onClick = { scope.launch { repo.deleteNote(note); nav.popBackStack() } }) { Text("Delete") }
                                }
                            }
                            activeViewerPhotoId?.let { photoId ->
                                InteractiveCaptureViewer(
                                    note = note,
                                    photoId = photoId,
                                    onDismiss = { activeViewerPhotoId = null },
                                    onSaveUpdated = { newTitle, newBody ->
                                        scope.launch { repo.saveNote(newTitle, newBody, note) }
                                    }
                                )
                            }
                        }
                    }
                    composable("note/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        val id = entry.arguments!!.getLong("id")
                        val note by remember(id) { repo.note(id) }.collectAsState(initial = null)
                        val photos by remember(id) { repo.notePhotos(id) }.collectAsState(initial = emptyList())
                        note?.let { NoteEditor(it, photos, onCancel = navigateBack) }
                    }
                    composable("reminders") {
                        val reminders by repo.reminders.collectAsState(initial = emptyList())
                        val (upcoming, past) = reminders.partition { it.dueAt > System.currentTimeMillis() }
                        CollectionScreen("Reminders", onBack = navigateBack) {
                            item(contentType = "actions") {
                                Button(onClick = { nav.navigate("reminders/new") }) { Text("+ Add reminder") }
                            }
                            if (reminders.isEmpty()) item {
                                EmptyCard("A little help remembering", "Create a reminder here or from your keyboard.")
                            }
                            for ((label, entries) in listOf("Upcoming" to upcoming, "Past" to past.asReversed())) {
                                if (entries.isEmpty()) continue
                                item(key = label, contentType = "heading") {
                                    Text(label, color = muted, style = MaterialTheme.typography.labelLarge)
                                }
                                items(entries, key = { it.id }, contentType = { "reminder" }) { reminder ->
                                    ReminderTile(
                                        reminder,
                                        onEdit = { nav.navigate("reminders/edit/${reminder.id}") },
                                        onDelete = { scope.launch { repo.deleteReminder(reminder) } }
                                    )
                                }
                            }
                        }
                    }
                    composable("reminders/new") { ReminderEditor(null, onSave = { title, due -> scope.launch { repo.saveReminder(title, due); navigateBack() } }, onCancel = navigateBack) }
                    composable("reminders/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        val id = entry.arguments!!.getLong("id")
                        val reminder by remember(id) { repo.reminder(id) }.collectAsState(initial = null)
                        reminder?.let {
                            ReminderEditor(it, onSave = { title, due -> scope.launch { repo.saveReminder(title, due, it); navigateBack() } }, onCancel = navigateBack)
                        }
                    }
                    composable("settings") {
                        var searching by rememberSaveable { mutableStateOf(false) }
                        var settingsQuery by rememberSaveable { mutableStateOf("") }
                        fun closeSearch() {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            searching = false
                            settingsQuery = ""
                        }
                        BackHandler(enabled = searching, onBack = ::closeSearch)
                        Screen("Settings", onBack = if (searching) ::closeSearch else navigateBack,
                            scrollState = settingsScroll, heading = {
                                SettingsSearchHeading(searching, settingsQuery, { settingsQuery = it },
                                    onOpen = { searching = true }, onClose = ::closeSearch)
                            }) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (settingsQuery.isNotBlank()) {
                                    val query = settingsQuery.trim()
                                    val matches = SettingsPage.entries.filter {
                                        "${it.title} ${it.summary}".contains(query, ignoreCase = true)
                                    }
                                    if (matches.isEmpty()) Text("No matching settings.", color = muted)
                                    matches.forEach { page -> SettingsLink(page) { nav.navigate(it.route) } }
                                } else {
                                SettingsCard("Personalization") {
                                    TextButton(onClick = { nav.navigate("onboarding") }) { Text("Quick setup") }
                                    SettingsLink(SettingsPage.ACCOUNT) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.APPEARANCE) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.KEYBOARD) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.KEYBOARD_STYLE) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.VOICE) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.TONES) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.LANGUAGES) { nav.navigate(it.route) }
                                }
                                SettingsCard("Intelligence & privacy") {
                                    SettingsLink(SettingsPage.MODELS) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.MEMORY) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.CAPTURE) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.PRIVACY) { nav.navigate(it.route) }
                                }
                                SettingsCard("Monolog") {
                                    SettingsLink(SettingsPage.ABOUT) { nav.navigate(it.route) }
                                }
                                }
                            }
                        }
                    }
                    composable("settings/{section}") { entry ->
                        val page = SettingsPage.entries.firstOrNull { it.id == entry.arguments?.getString("section") }
                            ?: return@composable
                        Screen(page.title, onBack = navigateBack) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                when (page) {
                                    SettingsPage.ABOUT -> AboutSettings { nav.navigate(SettingsPage.USAGE.route) }
                                    SettingsPage.USAGE -> KeyboardUsageSettings(settings)
                                    SettingsPage.ACCOUNT -> AccountBackupSettings()
                                    SettingsPage.WEB_CONNECT -> WebConnectScreen { nav.navigate(SettingsPage.ACCOUNT.route) }
                                    SettingsPage.MEMORY -> MemorySettings()
                                    SettingsPage.KEYBOARD_STYLE -> KeyboardStyleSettings(settings)
                                    SettingsPage.APPEARANCE -> {
                                        val borders by settings.keyBorders.collectAsState(false)
                                        val weather by settings.locationWeather.collectAsState(false)
                                        var photoStatus by remember { mutableStateOf<String?>(null) }
                                        val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                                            if (uri != null) scope.launch {
                                                try {
                                                    com.notokens.monolog.ui.theme.ThemeWallpaper.save(context, uri)
                                                    settings.activateWallpaper()
                                                    photoStatus = null
                                                } catch (cancelled: CancellationException) { throw cancelled }
                                                catch (_: Exception) { photoStatus = "Couldn’t open this image." }
                                            }
                                        }
                                        val selectPhoto = {
                                            wallpaperPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        }
                                        SettingsCard("Keyboard preview") {
                                            KeyboardThemePreview(settings, borders)
                                        }
                                        SettingsCard("Theme") {
                                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                ThemeChoice("Ink mode", "Muted charcoal", Glyph.INK, appearance.theme == ThemeMode.INK) {
                                                    scope.launch { settings.setThemeMode(ThemeMode.INK) }
                                                }
                                                ThemeChoice("Paper mode", "Quiet warm gray", Glyph.PAPER, appearance.theme == ThemeMode.PAPER) {
                                                    scope.launch { settings.setThemeMode(ThemeMode.PAPER) }
                                                }
                                                listOf(
                                                    Triple(ThemeMode.IRIS, "Iris", "Cool lavender and blue"),
                                                    Triple(ThemeMode.OCEAN, "Ocean", "Teal and sea glass"),
                                                    Triple(ThemeMode.TERRACOTTA, "Terracotta", "Peach and warm rose")
                                                ).forEach { (theme, title, detail) ->
                                                    ThemeChoice(title, detail, Glyph.PAPER, appearance.theme == theme) {
                                                        scope.launch { settings.setThemeMode(theme) }
                                                    }
                                                }
                                                ThemeChoice("Custom image", "Use one of your photos", Glyph.PHOTO, appearance.theme == ThemeMode.PHOTO) {
                                                    if (appearance.wallpaperRevision == 0L) selectPhoto()
                                                    else scope.launch { settings.setThemeMode(ThemeMode.PHOTO) }
                                                }
                                                if (appearance.wallpaperRevision != 0L) TextButton(selectPhoto) { Text("Change image") }
                                                photoStatus?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                                ThemeChoice("Follow system", "Match your device appearance", Glyph.GLOBE, appearance.theme == ThemeMode.SYSTEM) {
                                                    scope.launch { settings.setThemeMode(ThemeMode.SYSTEM) }
                                                }
                                            }
                                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = PaperPillBorder)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Keyboard key borders", Modifier.weight(1f), color = ink)
                                                Switch(borders, { checked -> scope.launch { settings.setKeyBorders(checked) } })
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("Reduce transparency", color = ink, style = MaterialTheme.typography.titleSmall)
                                                    Text("Use solid surfaces in the app and keyboard.", color = muted, style = MaterialTheme.typography.bodyMedium)
                                                }
                                                Switch(
                                                    checked = appearance.reduceTransparency,
                                                    onCheckedChange = { scope.launch { settings.setReduceTransparency(it) } },
                                                    modifier = Modifier.semantics { contentDescription = "Reduce transparency" }
                                                )
                                            }
                                        }
                                        SettingsCard("Weather") {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Weather from device location", Modifier.weight(1f), color = ink)
                                                Switch(weather, { checked -> scope.launch { settings.setLocationWeather(checked) } })
                                            }
                                            Text("Weather sends rounded coordinates to MET Norway and uses Android to look up the area name. Tap the weather card to grant location access.",
                                                color = muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    SettingsPage.KEYBOARD -> {
                                        KeyboardBehaviorSettings(settings)
                                        PersonalDictionarySettings(settings)
                                        val tapOpensShortcuts by settings.tapOpensShortcuts.collectAsState(false)
                                        val holdMs by settings.shortcutHoldMs.collectAsState(initial = 500)
                                        val shortcutHaptics by settings.shortcutHaptics.collectAsState(initial = true)
                                        SettingsCard("Quick shortcuts") {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("Tap M for shortcuts", color = ink)
                                                    Text(if (tapOpensShortcuts) "Hold M to capture context."
                                                        else "Tap M to capture context. Hold for shortcuts.",
                                                        color = muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                                Switch(tapOpensShortcuts, { checked ->
                                                    scope.launch { settings.setTapOpensShortcuts(checked) }
                                                }, modifier = Modifier.semantics { contentDescription = "Tap M for shortcuts" })
                                            }
                                            Text("Hold duration · $holdMs ms", color = muted)
                                            Slider(value = holdMs.toFloat(), onValueChange = { value -> scope.launch { settings.setShortcutHoldMs(value.toInt()) } },
                                                valueRange = 250f..1500f, steps = 4,
                                                modifier = Modifier.semantics { contentDescription = "Quick shortcuts hold duration" })
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Shortcut haptics", Modifier.weight(1f), color = ink)
                                                Switch(checked = shortcutHaptics, onCheckedChange = { scope.launch { settings.setShortcutHaptics(it) } },
                                                    modifier = Modifier.semantics { contentDescription = "Shortcut haptics" })
                                            }
                                        }
                                        SettingsCard("Setup & permissions") {
                                            SetupScreen(onRequestMicPermission = onRequestMicPermission)
                                        }
                                    }
                                    SettingsPage.VOICE -> VoiceInputSettings(settings, onRequestMicPermission)
                                    SettingsPage.TONES -> ToneSettings(settings)
                                    SettingsPage.LANGUAGES -> SettingsCard("Keyboard language and translation") {
                                        LanguageSettings(settings)
                                    }
                                    SettingsPage.MODELS -> AiSettings()
                                    SettingsPage.CAPTURE -> {
                                        SettingsCard("Android accessibility shortcut") {
                                            Text("Tap the Android shortcut for Quick Capture. Monolog opens its actions beside that button on devices that report a hold or double tap.",
                                                color = muted, style = MaterialTheme.typography.bodySmall)
                                            OutlinedButton(onClick = {
                                                com.notokens.monolog.capture.CaptureSetupActivity.openAccessibilitySettings(context)
                                            }) { Text("Open Accessibility settings") }
                                        }
                                        val captureBorderColorHex by settings.captureBorderColor.collectAsState(initial = "#FFB800")
                                        SettingsCard("Border color") {
                                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                listOf(
                                                    "#FFB800" to "Yellow",
                                                    "#4A70F5" to "Blue",
                                                    "#E53E3E" to "Red",
                                                    "#38A169" to "Green",
                                                    "#9F7AEA" to "Purple"
                                                ).forEach { (hex, name) ->
                                                    val isSelected = captureBorderColorHex.equals(hex, ignoreCase = true)
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(android.graphics.Color.parseColor(hex)))
                                                            .border(
                                                                if (isSelected) 3.dp else 1.dp,
                                                                if (isSelected) Color.White else Color.Transparent,
                                                                CircleShape
                                                            )
                                                            .selectable(isSelected, role = Role.RadioButton) { scope.launch { settings.setCaptureBorderColor(hex) } }
                                                            .semantics { contentDescription = name }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    SettingsPage.PRIVACY -> {
                                        val incognito by settings.incognito.collectAsState(initial = false)
                                        Column(Modifier.fillMaxWidth().glassSurface(24, GlassAccent.BLUE).padding(16.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                IconBadge(Glyph.PRIVACY)
                                                Text("Incognito mode", Modifier.weight(1f), color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                                Switch(
                                                    checked = incognito,
                                                    onCheckedChange = { scope.launch { settings.setIncognito(it) } },
                                                    modifier = Modifier.semantics { contentDescription = "Incognito mode" }
                                                )
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Text("Open the keyboard menu to turn on incognito for 15 minutes. Monolog automatically protects password fields.", color = muted, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        WebSettings(settings)
                                        KeyboardPrivacySettings(settings)
                                        SettingsCard("Local storage") {
                                            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear local data") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class SettingsPage(val id: String, val title: String, val summary: String, val icon: Glyph) {
    MEMORY("memory", "Journal memory", "Meaningful moments and offline search", Glyph.BOOK),
    ACCOUNT("account", "Account & backup", "Google account, encrypted Drive backup, and past years", Glyph.SHIELD),
    WEB_CONNECT("web-connect", "Web Connect", "Pair with the Monolog browser client", Glyph.GLOBE),
    APPEARANCE("appearance", "Themes & appearance", "Paper, Ink, keyboard preview, key borders and transparency", Glyph.INK),
    KEYBOARD("keyboard", "Keyboard & shortcuts", "Glide, suggestions, number row, autocorrect, grammar, hold duration and haptics", Glyph.SETTINGS),
    KEYBOARD_STYLE("keyboard-style", "Keyboard fonts & sounds", "Geist, text size, emoji rows and key tap sound", Glyph.SMILE),
    VOICE("voice", "VOX and Voice Features", "Speech packs, language, and dictation controls", Glyph.MIC),
    TONES("tones", "Tones", "Smart tone suggestions for work and chat", Glyph.BOLT),
    LANGUAGES("languages", "Languages & packs", "Layouts and offline translation", Glyph.GLOBE),
    MODELS("models", "On-device AI", "Offline text model downloads", Glyph.BOLT),
    CAPTURE("capture", "Quick Capture", "Screenshot border color", Glyph.CAPTURE),
    PRIVACY("privacy", "Privacy & data", "Personalise for you, clipboard, Draft Rescue, incognito, and deletion", Glyph.SHIELD),
    ABOUT("about", "About Monolog", "NoTokens, usage, policy, and acknowledgments", Glyph.INFO),
    USAGE("usage", "Usage statistics", "Keyboard activity and dictionary counts", Glyph.INFO);

    val route get() = "settings/$id"
}

@Composable
private fun SettingsLink(page: SettingsPage, onOpen: (SettingsPage) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { onOpen(page) }.padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(page.icon)
        Column(Modifier.weight(1f)) {
            Text(page.title, color = ink, style = MaterialTheme.typography.titleSmall)
            Text(page.summary, color = muted, style = MaterialTheme.typography.bodySmall)
        }
        MonologIcon(Glyph.ARROW, Modifier.size(16.dp), color = muted)
    }
}

@Composable
private fun Screen(
    title: String,
    showWeatherAndDate: Boolean = false,
    onBack: (() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    footer: (@Composable () -> Unit)? = null,
    heading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    ScreenFrame(onBack, showWeatherAndDate = showWeatherAndDate) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(vertical = 12.dp)) {
            if (heading != null) heading() else ScreenTitle(title)
            Spacer(Modifier.height(12.dp))
            content()
        }
        footer?.invoke()
    }
}

@Composable
private fun SettingsSearchHeading(
    expanded: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(expanded) {
        if (expanded) {
            withFrameNanos { }
            focus.requestFocus()
            keyboard?.show()
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
        val searchWidth by animateDpAsState(if (expanded) maxWidth else 48.dp, label = "Settings search width")
        if (!expanded) Box(Modifier.align(Alignment.CenterStart)) { ScreenTitle("Settings") }
        Row(Modifier.align(Alignment.CenterEnd).width(searchWidth).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically) {
            if (!expanded) {
                Spacer(Modifier.weight(1f))
                IconControl(Glyph.SEARCH, "Search settings", onOpen)
            } else {
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).fillMaxHeight().glassSurface(18)
                        .focusRequester(focus).semantics { contentDescription = "Search settings" },
                    decorationBox = { inner ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            MonologIcon(Glyph.SEARCH, Modifier.size(16.dp), muted)
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text("Search settings", color = muted,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                inner()
                            }
                        }
                    }
                )
                IconControl(Glyph.CLOSE, "Close settings search", onClose)
            }
        }
    }
}

@Composable
private fun CollectionScreen(
    title: String,
    showWeatherAndDate: Boolean = false,
    onBack: (() -> Unit)? = null,
    scrollState: LazyListState = rememberLazyListState(),
    footer: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    ScreenFrame(onBack, showWeatherAndDate = showWeatherAndDate) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = scrollState,
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(contentType = "title") { ScreenTitle(title) }
            content()
        }
        footer?.invoke()
    }
}

@Composable
private fun ScreenFrame(
    onBack: (() -> Unit)?,
    showWeatherAndDate: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    if (showWeatherAndDate) {
        WithWeatherDateCard { weatherDetails, weatherCard ->
            ScreenFrameLayout(onBack, weatherDetails) {
                weatherCard()
                content()
            }
        }
    } else {
        ScreenFrameLayout(onBack, content = content)
    }
}

@Composable
private fun ScreenFrameLayout(
    onBack: (() -> Unit)?,
    weatherDetails: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val onSettings = LocalSettingsAction.current
    val configuration = LocalConfiguration.current
    val showBadgeLabel = configuration.screenWidthDp >= 390 &&
        (configuration.fontScale <= 1.3f || configuration.screenWidthDp >= 412)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconControl(Glyph.BACK, "Back", onBack, frosted = true)
                MonologLogo(Modifier.size(if (weatherDetails != null) 36.dp else 38.dp))
                Text(
                    stringResource(R.string.app_name), color = ink,
                    style = if (weatherDetails != null) MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp)
                        else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 4.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (weatherDetails != null) {
                    Box(
                        Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clip(CircleShape)
                            .clickable(role = Role.Button) { onSettings(SettingsPage.MODELS) }
                            .semantics { if (!showBadgeLabel) contentDescription = "On-device AI" },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            Modifier.glassSurface(16).padding(horizontal = 6.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MonologIcon(Glyph.SHIELD, Modifier.size(12.dp), muted)
                            if (showBadgeLabel) Text("On-device AI", color = muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(
                        Modifier.widthIn(min = 44.dp).heightIn(min = 48.dp).clip(CircleShape)
                            .clickable(role = Role.Button) { onSettings(SettingsPage.WEB_CONNECT) }
                            .semantics { contentDescription = "Web Connect" }
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonologIcon(Glyph.GLOBE, Modifier.size(15.dp), ink)
                        if (configuration.screenWidthDp >= 360 && configuration.fontScale <= 1.3f) {
                            Text(if (configuration.screenWidthDp >= 412) "Web Connect" else "Connect",
                                color = ink, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    weatherDetails()
                    if (onBack == null) {
                        IconControl(Glyph.SETTINGS, "Settings", { onSettings(null) },
                            frosted = true, compact = true, iconOffsetX = (-3).dp)
                    }
                } else if (onBack == null) {
                    IconControl(Glyph.SETTINGS, "Settings", { onSettings(null) }, frosted = true)
                }
            }
            content()
        }
    }
}

@Composable
private fun JournalAskBar(
    query: String, onQuery: (String) -> Unit, hint: String, focusRequester: FocusRequester,
    answering: Boolean, onNew: () -> Unit, onAsk: () -> Unit
) {
    Row(Modifier.fillMaxWidth().glassSurface(26).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconControl(Glyph.PLUS, "New note", onNew)
        TextField(query, { onQuery(it.take(300)) },
            modifier = Modifier.weight(1f).focusRequester(focusRequester), singleLine = true,
            placeholder = { Text(hint, color = muted, maxLines = 1) },
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && !answering) onAsk() }))
        if (answering) ProcessingDots()
        else if (query.isNotBlank()) {
            IconControl(Glyph.CLOSE, "Clear search", { onQuery("") })
            IconControl(Glyph.BOLT, "Ask on-device AI", onAsk)
        } else {
            val keyboard = LocalSoftwareKeyboardController.current
            IconControl(Glyph.SEARCH, "Search or ask", { focusRequester.requestFocus(); keyboard?.show() })
        }
    }
}

@Composable
private fun ScreenTitle(title: String) {
    Text(title, color = ink, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun IconBadge(icon: Glyph) {
    Box(Modifier.size(32.dp).glassSurface(12), contentAlignment = Alignment.Center) {
        MonologIcon(icon, Modifier.size(18.dp))
    }
}

@Composable
private fun ThemeChoice(label: String, description: String, icon: Glyph, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) PaperKeyActive else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonologIcon(icon, Modifier.size(22.dp), if (selected) MaterialTheme.colorScheme.onPrimaryContainer else PaperAccentBlue)
        Column(Modifier.weight(1f)) {
            Text(label, color = ink, style = MaterialTheme.typography.titleSmall)
            Text(description, color = muted, style = MaterialTheme.typography.bodySmall)
        }
        RadioButton(selected, onClick = null, modifier = Modifier.padding(4.dp))
    }
}

@Composable
private fun DashboardTile(title: String, body: String, footer: String, icon: Glyph, accent: GlassAccent, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.glassSurface(24, accent).clickable(onClick = onClick).padding(12.dp)) {
        IconBadge(icon)
        Spacer(Modifier.height(4.dp))
        Text(title, color = ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(body, color = muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(footer, color = PaperAccentBlue, style = MaterialTheme.typography.labelMedium, maxLines = 2)
            MonologIcon(Glyph.ARROW, Modifier.size(12.dp), PaperAccentBlue)
        }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Column(Modifier.fillMaxWidth().glassSurface(24).padding(22.dp)) {
        Text(title, color = ink, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, color = muted)
    }
}

@Composable
private fun LibraryViewOptions(
    compact: Boolean, onToggleCompact: () -> Unit, sort: LibrarySort, onSort: (LibrarySort) -> Unit, enabled: Boolean
) {
    var showSort by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconControl(
            glyph = if (compact) Glyph.LIST else Glyph.GRID,
            label = if (compact) "Show grid view" else "Show compact list",
            onClick = onToggleCompact,
            modifier = Modifier.semantics { selected = compact },
            enabled = enabled,
            frosted = true,
            compact = true
        )
        Box {
            IconControl(sort.icon, "Sort: ${sort.label}", { showSort = true },
                frosted = true, compact = true, enabled = enabled)
            DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }, shape = RoundedCornerShape(20.dp)) {
                LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = { RadioButton(selected = sort == option, onClick = null) },
                        modifier = Modifier.semantics { selected = sort == option },
                        onClick = { onSort(option); showSort = false }
                    )
                }
            }
        }
    }
}

private enum class LibrarySort(val label: String, val icon: Glyph, val comparator: Comparator<Note>) {
    NEWEST("Newest first", Glyph.SORT_DOWN, compareByDescending<Note> { it.updatedAt }.thenByDescending { it.id }),
    OLDEST("Oldest first", Glyph.SORT_UP, compareBy<Note> { it.updatedAt }.thenBy { it.id }),
    TITLE_ASC("Title A–Z", Glyph.SORT_TITLE_DOWN, compareBy<Note, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.id }),
    TITLE_DESC("Title Z–A", Glyph.SORT_TITLE_UP, compareBy<Note, String>(String.CASE_INSENSITIVE_ORDER.reversed()) { it.title }.thenBy { it.id })
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun NoteTile(
    note: Note,
    photos: List<NotePhotoRef>,
    compact: Boolean,
    selecting: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit
) {
    val decoded = remember(note.body) { TagMetadataCodec.decode(note.body) }
    val excerpt = remember(decoded.cleanBody) { markdownExcerpt(decoded.cleanBody).take(120) }
    val audio = remember(note.body) { VoiceNoteBody.audio(note.body) ?: decoded.bottomAudio ?: decoded.tags.firstNotNullOfOrNull { it.audio } }
    val tile = Modifier
        .fillMaxWidth()
        .glassSurface(20, GlassAccent.LAVENDER)
        .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)) else Modifier)
        .combinedClickable(
            role = Role.Button,
            onLongClickLabel = if (isSelected) "Deselect item" else "Select item",
            onLongClick = onSelect,
            onClick = onClick
        )
        .semantics { if (selecting) selected = isSelected }
        .padding(12.dp)
    if (compact) {
        Row(tile, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (photos.isNotEmpty()) {
                JournalPhotoImage(
                    JournalPhoto.Saved(photos.first().id), "Attached photo",
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop, thumbnailSize = 48.dp
                )
            } else {
                IconBadge(if (audio != null || decoded.bottomIsVoice) Glyph.MIC else Glyph.PAPER)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(note.title, color = ink, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatTime(note.updatedAt), color = muted, style = MaterialTheme.typography.labelSmall)
            }
            if (selecting) Checkbox(checked = isSelected, onCheckedChange = null)
            else MonologIcon(Glyph.ARROW, Modifier.size(14.dp), PaperAccentBlue)
        }
        return
    }

    Column(
        Modifier.heightIn(min = 140.dp).then(tile),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (photos.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    JournalPhotoImage(
                        JournalPhoto.Saved(photos.first().id),
                        "Attached photo",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        thumbnailSize = 210.dp
                    )
                    if (decoded.tags.isNotEmpty() || decoded.bottomIsVoice) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.72f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (decoded.bottomIsVoice || decoded.tags.any { it.isVoice }) {
                                    MonologIcon(Glyph.MIC, Modifier.size(10.dp), PaperAccentBlue)
                                } else {
                                    MonologIcon(Glyph.PIN, Modifier.size(10.dp), PaperAccentBlue)
                                }
                                Text(
                                    if (decoded.tags.isNotEmpty()) "${decoded.tags.size}" else "Voice",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else if (audio != null) {
                Box(Modifier.fillMaxWidth().height(106.dp).glassSurface(17, GlassAccent.LAVENDER),
                    contentAlignment = Alignment.Center) {
                    if (selecting) MonologIcon(Glyph.MIC)
                    else VoiceNotePlayback(audio, "Play voice note", onLongClick = onSelect)
                }
            }
            Text(
                note.title,
                color = ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (photos.isEmpty()) {
                if (decoded.bottomIsVoice && audio == null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PaperAccentBlue.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            MonologIcon(Glyph.MIC, Modifier.size(12.dp), PaperAccentBlue)
                            repeat(4) { i ->
                                Box(
                                    Modifier
                                        .width(2.dp)
                                        .height(if (i % 2 == 0) 6.dp else 10.dp)
                                        .background(PaperAccentBlue, RoundedCornerShape(1.dp))
                                )
                            }
                            Text("Voice note", color = PaperAccentBlue, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else if (excerpt.isNotEmpty()) {
                    Text(
                        excerpt,
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (photos.isNotEmpty()) "${photos.size} photo" else formatTime(note.updatedAt),
                modifier = Modifier.weight(1f),
                color = if (photos.isNotEmpty()) PaperAccentBlue else muted,
                style = MaterialTheme.typography.labelSmall
            )
            if (selecting) Checkbox(checked = isSelected, onCheckedChange = null)
            else MonologIcon(Glyph.ARROW, Modifier.size(14.dp), PaperAccentBlue)
        }
    }
}

@Composable
private fun ReminderTile(reminder: Reminder, onEdit: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().glassSurface(20, GlassAccent.BLUE).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Glyph.BELL)
            Column(Modifier.weight(1f)) {
                Text(reminder.title, color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(formatTime(reminder.dueAt), color = muted, style = MaterialTheme.typography.bodyMedium)
            }
            IconControl(Glyph.ARROW, "Edit reminder", onEdit)
        }
        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.End)) { Text("Delete") }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete reminder?") },
            text = { Text(reminder.title) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun rememberEditorFocusRequester(): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusRequester) {
        if (!focusRequested) {
            focusRequester.requestFocus()
            keyboard?.show()
            focusRequested = true
        }
    }
    return focusRequester
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NoteEditor(initial: Note?, savedPhotos: List<NotePhotoRef>, onCancel: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    val capture = remember(initial?.body) { initial?.let { TagMetadataCodec.decode(it.body) } }
    val isCapture = capture?.hasMetadata == true
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(if (isCapture) capture.bottomComment else capture?.cleanBody.orEmpty()))
    }
    var formatting by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var removed by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var preview by rememberSaveable { mutableStateOf(initial != null && !isCapture) }
    var voiceAudio by remember(initial?.id) { mutableStateOf(initial?.body?.let(VoiceNoteBody::audio)) }
    var captureAudio by remember(initial?.id) { mutableStateOf(capture?.bottomAudio) }
    var voiceBusy by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var saving by remember { mutableStateOf(false) }
    var checkingPhotos by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val titleFocus = rememberEditorFocusRequester()
    val bodyFocus = remember { FocusRequester() }
    val photos = savedPhotos.filterNot { it.id in removed }.map { JournalPhoto.Saved(it.id) } + picked.map { JournalPhoto.Picked(it) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoCodec.MAX_ATTACHMENTS)) { uris ->
        val freeSlots = (PhotoCodec.MAX_ATTACHMENTS - photos.size).coerceAtLeast(0)
        val next = uris.map { it.toString() }.distinct().filterNot { it in picked }
        if (next.isNotEmpty()) {
            checkingPhotos = true
            scope.launch {
                try {
                    val results = withContext(Dispatchers.IO) {
                        next.map { uri ->
                            uri to try {
                                PhotoCodec.checkNoteImageSize(context.contentResolver, Uri.parse(uri))
                                null
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: PhotoCodec.NoteImageTooLarge) {
                                failure.message
                            } catch (_: Exception) {
                                "Couldn’t open one of the selected images."
                            }
                        }
                    }
                    val valid = results.filter { it.second == null }.map { it.first }
                    picked = ArrayList(picked + valid.take(freeSlots))
                    error = results.firstNotNullOfOrNull { it.second } ?: if (valid.size > freeSlots) {
                        "You can attach up to ${PhotoCodec.MAX_ATTACHMENTS} photos per entry."
                    } else null
                } finally {
                    checkingPhotos = false
                }
            }
        }
    }
    val cancel = { if (!saving) onCancel() }
    val editorFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent
    )
    Screen(if (initial == null) "New note" else "Edit note", onBack = cancel) {
        Column(
            Modifier.fillMaxWidth().glassSurface(24).padding(vertical = 8.dp)
        ) {
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Title", color = muted) },
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).semantics { contentDescription = "Title" },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                colors = editorFieldColors
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = PaperPillBorder)
            if (!isCapture) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(!preview, { preview = false }, { Text("Write") },
                        modifier = Modifier.align(Alignment.CenterVertically), shape = CircleShape)
                    FilterChip(preview, {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        preview = true
                    }, { Text("Preview") }, modifier = Modifier.align(Alignment.CenterVertically), shape = CircleShape)
                    if (!preview) TextButton(
                        onClick = { formatting = !formatting },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Text(if (formatting) "Hide formatting" else "Formatting")
                    }
                }
            }
            if (preview) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (body.text.isBlank()) Text("Your formatted entry will appear here.", color = muted)
                    else JournalMarkdown(body.text, Modifier.fillMaxWidth()) { index, checked ->
                        body = body.copy(text = body.text.replaceRange(index, index + 1, if (checked) "x" else " "))
                    }
                }
            } else {
                if (!isCapture && formatting) FlowRow(
                    Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    NoteFormat.entries.forEach { format ->
                        TextButton(onClick = {
                            body = formatNote(body, format)
                            bodyFocus.requestFocus()
                            keyboard?.show()
                        }) { Text(format.label, fontWeight = if (format == NoteFormat.Bold) FontWeight.Bold else null) }
                    }
                }
                TextField(
                    value = body,
                    onValueChange = { body = if (isCapture) it else continueNoteList(body, it) },
                    placeholder = { Text("What would you like to remember?", color = muted) },
                    modifier = Modifier.fillMaxWidth().focusRequester(bodyFocus).semantics { contentDescription = "Your note" },
                    minLines = 5,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = editorFieldColors
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isCapture && capture.tags.isNotEmpty()) {
                    Text("Pinned notes", color = ink, style = MaterialTheme.typography.titleSmall)
                    capture.tags.forEach { tag ->
                        Row(Modifier.fillMaxWidth().glassSurface(16).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MonologIcon(if (tag.isVoice) Glyph.MIC else Glyph.PIN, Modifier.size(18.dp))
                            Text(tag.text, color = ink, modifier = Modifier.weight(1f))
                        }
                    }
                    Text("Open the photo from its saved entry to edit pinned notes.", color = muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (photos.isNotEmpty()) {
                    JournalPhotoStrip(photos) { photo ->
                        when (photo) {
                            is JournalPhoto.Saved -> removed = ArrayList(removed + photo.id)
                            is JournalPhoto.Picked -> picked = ArrayList(picked - photo.uri)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = PaperPillBorder)
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = photos.size < PhotoCodec.MAX_ATTACHMENTS && !saving && !checkingPhotos
                ) {
                    MonologIcon(Glyph.PHOTO, Modifier.size(18.dp), PaperAccentBlue)
                    Spacer(Modifier.width(8.dp))
                    Text(if (photos.isEmpty()) "Add photos" else "Add photos (${photos.size}/${PhotoCodec.MAX_ATTACHMENTS})")
                }
                VoiceNoteControl(enabled = !saving, onPermission = { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) },
                    onBusy = { voiceBusy = it }, audioOnly = true, onVoice = { voice ->
                        if (isCapture) captureAudio = voice.audio else voiceAudio = voice.audio
                    })
                (if (isCapture) captureAudio else voiceAudio)?.let { VoiceNotePlayback(it) }
            }
            Text("Images must be smaller than 5 MB each.", Modifier.padding(start = 16.dp, bottom = 4.dp),
                color = muted, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !saving && !checkingPhotos && !voiceBusy && (title.isNotBlank() || body.text.isNotBlank() || photos.isNotEmpty() || voiceAudio != null || captureAudio != null),
                onClick = {
                    saving = true
                    error = null
                    val changes = picked.toList()
                    val removals = removed.toList()
                    val currentTitle = title
                    val currentBody = body.text
                    scope.launch {
                        try {
                            val bytes = withContext(Dispatchers.IO) {
                                changes.map { uri -> PhotoCodec.noteImage(context.contentResolver, Uri.parse(uri)) }
                            }
                            val savedBody = if (isCapture) {
                                TagMetadataCodec.encodeCapture(capture.tags, capture.bottomIsVoice || captureAudio != null,
                                    currentBody, capture.additionalBody, captureAudio, capture.imageKeywords)
                            } else {
                                val updated = initial?.let { TagMetadataCodec.replaceVisibleBody(it.body, currentBody) } ?: currentBody
                                VoiceNoteBody.attach(updated, voiceAudio)
                            }
                            MonologData.repository(context).saveNote(
                                currentTitle.ifBlank { if (voiceAudio != null && currentBody.isBlank()) "Voice note" else "" },
                                savedBody, initial, NotePhotoChanges(removals, bytes)
                            )
                            onCancel()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (failure: PhotoCodec.NoteImageTooLarge) {
                            error = failure.message
                        } catch (_: Exception) {
                            error = "Couldn’t save this entry. Check your photos and try again."
                        } finally {
                            saving = false
                        }
                    }
                }
            ) { Text(if (saving) "Saving…" else "Save") }
            OutlinedButton(onClick = cancel, enabled = !saving) { Text("Cancel") }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReminderEditor(initial: Reminder?, onSave: (String, Long) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val inkMode = LocalMonologDark.current
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var dueAt by rememberSaveable { mutableStateOf(initial?.dueAt ?: (System.currentTimeMillis() + 3_600_000)) }
    var invalidTime by remember { mutableStateOf(false) }
    val updateTime: (Long) -> Unit = { dueAt = it; invalidTime = false }
    val titleFocus = rememberEditorFocusRequester()
    Screen(if (initial == null) "New reminder" else "Edit reminder", onBack = onCancel) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("What should I remind you?") },
            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { updateTime(System.currentTimeMillis() + 3_600_000) }, label = { Text("In 1 hour") })
            AssistChip(onClick = {
                updateTime(ZonedDateTime.now().plusDays(1).toLocalDate().atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }, label = { Text("Tomorrow at 9") })
        }
        Row(
            Modifier.fillMaxWidth().glassSurface(24, GlassAccent.BLUE)
                .clickable(onClickLabel = "Choose date and time") { pickReminderTime(context, dueAt, inkMode, updateTime) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonologIcon(Glyph.CLOCK)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Due date and time", color = ink, fontWeight = FontWeight.Medium)
                Text(formatTime(dueAt), color = muted)
            }
            MonologIcon(Glyph.ARROW, Modifier.size(16.dp), PaperAccentBlue)
        }
        if (invalidTime) Text("Choose a time in the future.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (dueAt <= System.currentTimeMillis()) invalidTime = true
                else onSave(title.ifBlank { "Reminder" }, dueAt)
            }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
