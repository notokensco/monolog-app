package com.notokens.monolog.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.notokens.monolog.R
import androidx.compose.ui.layout.ContentScale
import com.notokens.monolog.capture.captureToJournal
import com.notokens.monolog.capture.TagMetadataCodec
import com.notokens.monolog.ime.vox.VoiceNoteBody
import com.notokens.monolog.capture.copyCompanionWindow
import com.notokens.monolog.data.*
import com.notokens.monolog.setup.SetupScreen
import com.notokens.monolog.ui.theme.*
import java.text.DateFormat
import java.text.SimpleDateFormat
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
private val LocalSettingsAction = compositionLocalOf<() -> Unit> { error("Settings navigation is missing") }

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
    TODAY("today"), JOURNAL("journal"), LIBRARY("library"), SETTINGS("settings")
}

@Composable
fun CompanionApp(
    settings: SettingsStore,
    appearance: Appearance,
    destination: CompanionDestination?,
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
    val route = backStack?.destination?.route ?: CompanionDestination.TODAY.route
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val navigateBack: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        nav.popBackStack()
    }
    val openSettings: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        nav.navigate(CompanionDestination.SETTINGS.route) { launchSingleTop = true }
        scope.launch { settingsScroll.scrollTo(0) }
    }
    LaunchedEffect(destination) {
        if (destination != null) {
            when (destination) {
                CompanionDestination.SETTINGS -> openSettings()
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
        text = { Text("This deletes all notes, photos, and reminders from this device and cancels their notifications.") },
        confirmButton = { Button(onClick = {
            scope.launch { repo.clearAllData(); settings.setIncognito(false); confirmClear = false }
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
                            onAccount = { finishSetup(SettingsPage.ACCOUNT.route) },
                            onModels = { finishSetup(SettingsPage.MODELS.route) },
                            onMemory = { finishSetup(SettingsPage.MEMORY.route) })
                    }
                    composable("today") {
                        Screen("Your day, remembered.", showDate = true) {
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
                    composable("journal") {
                        val allNotes by repo.notes.collectAsState(initial = emptyList())
                        val notes = visibleBackupYears(allNotes)
                        CollectionScreen("Journal") {
                            item(contentType = "memory") {
                                MemoryJournal(
                                    onOpenNote = { nav.navigate("note/$it") },
                                    onOpenReminder = { nav.navigate("reminder/edit/$it") },
                                    onNewNote = { nav.navigate("note/new") },
                                    visibleNotes = notes
                                )
                            }
                            item { ArchivedYearsLink { nav.navigate(SettingsPage.ACCOUNT.route) } }
                        }
                    }
                    composable("library") {
                        var filter by rememberSaveable { mutableStateOf("All") }
                        var query by rememberSaveable { mutableStateOf("") }
                        var creating by remember { mutableStateOf(false) }
                        var capturePending by remember { mutableStateOf(false) }
                        val screenScope = rememberCoroutineScope()
                        val allNotes by repo.notes.collectAsState(initial = emptyList())
                        val notes = visibleBackupYears(allNotes)
                        val photoRefs by repo.notePhotos.collectAsState(initial = emptyList())
                        val photosByNote = remember(photoRefs) { photoRefs.groupBy { it.noteId } }
                        val entries = remember(notes, photosByNote, filter, query) {
                            val candidates = notes.filter { note ->
                                val metadata = TagMetadataCodec.decode(note.body)
                                when (filter) {
                                    "Photos" -> note.id in photosByNote
                                    "Screenshots" -> metadata.tags.isNotEmpty() || note.body.contains("<!-- MONOLOG_CAPTURE:")
                                    "Voice" -> metadata.bottomIsVoice || metadata.tags.any { it.isVoice } || VoiceNoteBody.audio(note.body) != null
                                    else -> true
                                }
                            }
                            if (query.isBlank()) candidates else candidates.matchingJournalEntries(query)
                        }
                        ScreenFrame(null) {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Context library", color = ink, style = MaterialTheme.typography.headlineMedium,
                                                fontWeight = FontWeight.SemiBold)
                                            Text("Saved moments, ready when you need them", color = muted,
                                                style = MaterialTheme.typography.bodyMedium)
                                        }
                                        Box {
                                            IconControl(Glyph.PLUS, "Create", { creating = true }, frosted = true)
                                            DropdownMenu(expanded = creating, onDismissRequest = { creating = false }) {
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
                                }
                                item {
                                    TextField(value = query, onValueChange = { query = it.take(240) },
                                        modifier = Modifier.fillMaxWidth().glassSurface(28), singleLine = true,
                                        placeholder = { Text("Search photos, notes & memories") },
                                        leadingIcon = { MonologIcon(Glyph.SEARCH) },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                                }
                                item {
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("All", "Photos", "Screenshots", "Voice").forEach { label ->
                                            FilterChip(filter == label, { filter = label }, { Text(label) })
                                        }
                                    }
                                }
                                if (entries.isEmpty()) item {
                                    EmptyCard(if (query.isNotBlank()) "No matching memories" else "Your memories start here",
                                        if (query.isNotBlank()) "Try another search or filter." else "Tap + to add a note or photo. Use M on the keyboard to capture a screen.")
                                }
                                item { ArchivedYearsLink { nav.navigate(SettingsPage.ACCOUNT.route) } }
                                items(entries.chunked(2), key = { row -> row.first().id }) { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        row.forEach { note ->
                                            Box(Modifier.weight(1f)) {
                                                NoteTile(note, photosByNote[note.id].orEmpty()) { nav.navigate("note/${note.id}") }
                                            }
                                        }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                                item {
                                    Row(Modifier.fillMaxWidth().glassSurface(24).clickable { nav.navigate("journal") }.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        MonologIcon(Glyph.BOOK)
                                        Text("Open today's journal", Modifier.weight(1f), color = ink)
                                        MonologIcon(Glyph.ARROW)
                                    }
                                }
                            }
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
                        Screen("Settings", onBack = navigateBack, scrollState = settingsScroll) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                SettingsCard("Personalization") {
                                    TextButton(onClick = { nav.navigate("onboarding") }) { Text("Quick setup") }
                                    SettingsLink(SettingsPage.ACCOUNT) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.APPEARANCE) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.KEYBOARD) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.TONES) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.LANGUAGES) { nav.navigate(it.route) }
                                }
                                SettingsCard("Intelligence & privacy") {
                                    SettingsLink(SettingsPage.MODELS) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.MEMORY) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.CAPTURE) { nav.navigate(it.route) }
                                    SettingsLink(SettingsPage.PRIVACY) { nav.navigate(it.route) }
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
                                    SettingsPage.ACCOUNT -> AccountBackupSettings()
                                    SettingsPage.MEMORY -> MemorySettings()
                                    SettingsPage.APPEARANCE -> {
                                        SettingsCard("Theme") {
                                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                ThemeChoice("Ink mode", "Charcoal glass with blue and violet accents", Glyph.INK, appearance.theme == ThemeMode.INK) {
                                                    scope.launch { settings.setThemeMode(ThemeMode.INK) }
                                                }
                                                ThemeChoice("Paper mode", "Pearl glass with soft blue and blush accents", Glyph.PAPER, appearance.theme == ThemeMode.PAPER) {
                                                    scope.launch { settings.setThemeMode(ThemeMode.PAPER) }
                                                }
                                                ThemeChoice("Follow system", "Match your device appearance", Glyph.GLOBE, appearance.theme == ThemeMode.SYSTEM) {
                                                    scope.launch { settings.setThemeMode(ThemeMode.SYSTEM) }
                                                }
                                            }
                                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = PaperPillBorder)
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
                                    }
                                    SettingsPage.KEYBOARD -> {
                                        val autocorrect by settings.autocorrect.collectAsState(initial = true)
                                        val swipeTyping by settings.swipeTyping.collectAsState(initial = true)
                                        val longPressCharacters by settings.longPressCharacters.collectAsState(initial = true)
                                        SettingsCard("Typing") {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("Autocorrect", color = ink)
                                                    Text("Correct English typos on space or punctuation. Backspace immediately to undo. Paused in private fields.", color = muted,
                                                        style = MaterialTheme.typography.bodySmall)
                                                }
                                                Switch(autocorrect, { scope.launch { settings.setAutocorrect(it) } },
                                                    modifier = Modifier.semantics { contentDescription = "Autocorrect" })
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("Swipe to type", color = ink)
                                                    Text("Glide across letters in English or Hinglish.", color = muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                                Switch(checked = swipeTyping, onCheckedChange = { scope.launch { settings.setSwipeTyping(it) } },
                                                    modifier = Modifier.semantics { contentDescription = "Swipe to type" })
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("Long-press numbers & symbols", color = ink)
                                                    Text("Hold Q–P for 1–0. Hold other English letters for the symbol shown above them.", color = muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                                Switch(checked = longPressCharacters, onCheckedChange = { scope.launch { settings.setLongPressCharacters(it) } },
                                                    modifier = Modifier.semantics { contentDescription = "Long-press numbers and symbols" })
                                            }
                                        }
                                        PersonalDictionarySettings(settings)
                                        val holdMs by settings.shortcutHoldMs.collectAsState(initial = 500)
                                        val shortcutHaptics by settings.shortcutHaptics.collectAsState(initial = true)
                                        SettingsCard("Quick shortcuts") {
                                            Text("Hold M to open: $holdMs ms", color = muted)
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
                                    SettingsPage.TONES -> ToneSettings(settings)
                                    SettingsPage.LANGUAGES -> SettingsCard("Keyboard language and translation") {
                                        LanguageSettings(settings)
                                    }
                                    SettingsPage.MODELS -> AiSettings()
                                    SettingsPage.CAPTURE -> {
                                        val floatingDock by settings.floatingContextDock.collectAsState(initial = false)
                                        val systemShortcut by com.notokens.monolog.capture.ScreenCaptureService.systemShortcutAssigned.collectAsState()
                                        SettingsCard("Floating context button") {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Circular shortcut dock", Modifier.weight(1f), color = ink)
                                                Switch(floatingDock, { scope.launch { settings.setFloatingContextDock(it) } })
                                            }
                                            Text("Enable Quick Capture in Accessibility settings. Tap the Monolog button to capture, or hold to open its dock.",
                                                color = muted, style = MaterialTheme.typography.bodySmall)
                                            if (floatingDock && systemShortcut) {
                                                Text("Turn off Monolog’s Android accessibility shortcut to switch to the movable button. Keep Quick Capture enabled.",
                                                    color = muted, style = MaterialTheme.typography.bodySmall)
                                            }
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
    APPEARANCE("appearance", "Appearance", "Paper, Ink, and transparency", Glyph.INK),
    KEYBOARD("keyboard", "Keyboard & shortcuts", "Setup, hold duration, and haptics", Glyph.SETTINGS),
    TONES("tones", "Tones", "Smart tone suggestions for work and chat", Glyph.BOLT),
    LANGUAGES("languages", "Languages & packs", "Layouts and offline translation", Glyph.GLOBE),
    MODELS("models", "On-device AI", "Gemini Nano and Gemma downloads", Glyph.BOLT),
    CAPTURE("capture", "Quick Capture", "Screenshot border color", Glyph.CAPTURE),
    PRIVACY("privacy", "Privacy & data", "Incognito, Web suggestions, and storage", Glyph.SHIELD);

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
    showDate: Boolean = false,
    onBack: (() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable () -> Unit
) {
    ScreenFrame(onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(vertical = 12.dp)) {
            ScreenTitle(title, showDate)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun CollectionScreen(
    title: String,
    showDate: Boolean = false,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    ScreenFrame(onBack) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(contentType = "title") { ScreenTitle(title, showDate) }
            content()
        }
    }
}

@Composable
private fun ScreenFrame(onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val onSettings = LocalSettingsAction.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconControl(Glyph.BACK, "Back", onBack, frosted = true)
                MonologLogo(Modifier.size(38.dp))
                Text(
                    stringResource(R.string.app_name), color = ink, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (onBack == null) IconControl(Glyph.SETTINGS, "Settings", onSettings, frosted = true)
            }
            content()
        }
    }
}

@Composable
private fun ScreenTitle(title: String, showDate: Boolean) {
    if (showDate) {
        Row(
            Modifier.fillMaxWidth().glassSurface(24).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            MonologIcon(Glyph.CLOCK, Modifier.size(16.dp), muted)
            Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), color = muted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
    }
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
private fun NoteTile(
    note: Note,
    photos: List<NotePhotoRef>,
    onPhotoClick: ((Long) -> Unit)? = null,
    onClick: () -> Unit
) {
    val decoded = remember(note.body) { TagMetadataCodec.decode(note.body) }
    val excerpt = remember(decoded.cleanBody) { markdownExcerpt(decoded.cleanBody).take(120) }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .glassSurface(20, GlassAccent.LAVENDER)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (photos.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPhotoClick?.invoke(photos.first().id) ?: onClick() }
                ) {
                    JournalPhotoImage(
                        JournalPhoto.Saved(photos.first().id),
                        "Attached photo",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        thumbnailSize = 140.dp
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
                if (decoded.bottomIsVoice) {
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
                color = if (photos.isNotEmpty()) PaperAccentBlue else muted,
                style = MaterialTheme.typography.labelSmall
            )
            MonologIcon(Glyph.ARROW, Modifier.size(14.dp), PaperAccentBlue)
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
    var error by remember { mutableStateOf<String?>(null) }
    val titleFocus = rememberEditorFocusRequester()
    val photos = savedPhotos.filterNot { it.id in removed }.map { JournalPhoto.Saved(it.id) } + picked.map { JournalPhoto.Picked(it) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoCodec.MAX_ATTACHMENTS)) { uris ->
        val freeSlots = (PhotoCodec.MAX_ATTACHMENTS - photos.size).coerceAtLeast(0)
        val next = uris.map { it.toString() }.distinct().filterNot { it in picked }
        picked = ArrayList(picked + next.take(freeSlots))
        error = if (next.size > freeSlots) "You can attach up to ${PhotoCodec.MAX_ATTACHMENTS} photos per entry." else null
    }
    val cancel = { if (!saving) onCancel() }
    Screen(if (initial == null) "New note" else "Edit note", onBack = cancel) {
        Column(
            Modifier.fillMaxWidth().glassSurface(24, GlassAccent.LAVENDER).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text("Title") },
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus), singleLine = true, shape = RoundedCornerShape(12.dp)
            )
            if (!isCapture) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!preview, { preview = false }, { Text("Write") })
                FilterChip(preview, {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    preview = true
                }, { Text("Preview") })
            }
            VoiceNoteControl(enabled = !saving, onPermission = { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) },
                onBusy = { voiceBusy = it }, onVoice = { voice ->
                    if (isCapture) captureAudio = voice.audio else voiceAudio = voice.audio
                    val text = listOf(body.text, voice.transcript).filter(String::isNotBlank).joinToString("\n\n")
                    body = TextFieldValue(text, TextRange(text.length))
                    preview = false
                })
            (if (isCapture) captureAudio else voiceAudio)?.let { VoiceNotePlayback(it) }
            if (preview) {
                if (body.text.isBlank()) Text("Your formatted entry will appear here.", color = muted)
                else JournalMarkdown(body.text, Modifier.fillMaxWidth().padding(4.dp))
            } else {
                if (!isCapture) TextButton(onClick = { formatting = !formatting }) {
                    Text(if (formatting) "Hide formatting" else "Formatting")
                }
                if (!isCapture && formatting) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { body = formatSelection(body, "\n## ", "", "Heading") }) { Text("Heading") }
                    TextButton(onClick = { body = formatSelection(body, "**", "**", "Bold text") }) { Text("Bold", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { body = formatSelection(body, "\n- ", "", "List item") }) { Text("List") }
                }
                OutlinedTextField(
                    value = body, onValueChange = { body = it }, label = { Text("Your note") }, placeholder = { Text("What would you like to remember?") },
                    modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12, shape = RoundedCornerShape(12.dp)
                )
            }
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
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = photos.size < PhotoCodec.MAX_ATTACHMENTS && !saving
            ) {
                MonologIcon(Glyph.PHOTO, Modifier.size(16.dp), PaperAccentBlue)
                Spacer(Modifier.width(8.dp))
                Text(if (photos.isEmpty()) "Add photos" else "Add photos (${photos.size}/${PhotoCodec.MAX_ATTACHMENTS})")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !saving && !voiceBusy && (title.isNotBlank() || body.text.isNotBlank() || photos.isNotEmpty() || voiceAudio != null || captureAudio != null),
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
                                changes.map { uri ->
                                    val image = PhotoCodec.decode(context.contentResolver, Uri.parse(uri))
                                    try { PhotoCodec.jpeg(image) } finally { image.recycle() }
                                }
                            }
                            val savedBody = if (isCapture) {
                                TagMetadataCodec.encodeCapture(capture.tags, capture.bottomIsVoice || captureAudio != null,
                                    currentBody, capture.additionalBody, captureAudio)
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

private fun formatSelection(value: TextFieldValue, prefix: String, suffix: String, placeholder: String): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val content = value.text.substring(start, end).ifEmpty { placeholder }
    val opening = if (prefix.startsWith('\n') && (start == 0 || value.text.getOrNull(start - 1) == '\n')) prefix.drop(1) else prefix
    val text = value.text.replaceRange(start, end, opening + content + suffix)
    return TextFieldValue(text, TextRange(start + opening.length, start + opening.length + content.length))
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
