package com.notokens.monolog.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.notokens.monolog.R
import com.notokens.monolog.data.*
import com.notokens.monolog.setup.SetupScreen
import com.notokens.monolog.ui.theme.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    TODAY("today"), JOURNAL("journal"), LIBRARY("library"), SETTINGS("settings")
}

@Composable
fun CompanionLoadingScreen() {
    Box(Modifier.fillMaxSize().monologBackdrop().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(painterResource(R.drawable.monolog_brand_logo), contentDescription = null, modifier = Modifier.size(132.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.companion_brand_line), color = muted, style = MaterialTheme.typography.bodyLarge)
        }
    }
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
    val notes by repo.notes.collectAsState(initial = emptyList())
    val photoRefs by repo.notePhotos.collectAsState(initial = emptyList())
    val photosByNote = remember(photoRefs) { photoRefs.groupBy { it.noteId } }
    val reminders by repo.reminders.collectAsState(initial = emptyList())
    val incognito by settings.incognito.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
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
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

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
                NavHost(nav, startDestination = CompanionDestination.TODAY.route, modifier = Modifier.padding(padding)) {
                    composable("today") {
                        Screen("Your day, remembered.", showDate = true) {
                            val tileHeight = ((LocalConfiguration.current.screenHeightDp - 512) / 2).coerceIn(124, 152).dp
                            val zone = ZoneId.systemDefault()
                            val today = LocalDate.now(zone)
                            val todayNote = notes.firstOrNull { Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() == today }
                            Column(
                                Modifier.fillMaxWidth().glassSurface(24, GlassAccent.BLUE).journalPageAccent()
                                    .clickable { nav.navigate(todayNote?.let { "note/edit/${it.id}" } ?: "note/new") }.padding(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconBadge(Glyph.BOOK)
                                    Text("Today’s journal", color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.height(8.dp))
                                val excerpt = remember(todayNote) { todayNote?.let { markdownExcerpt(it.body).take(130).ifBlank { it.title } } }
                                Text(excerpt ?: "A little space for your thoughts, conversations, and progress.", color = muted, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Continue writing", color = PaperAccentBlue, style = MaterialTheme.typography.labelLarge)
                                    MonologIcon(Glyph.ARROW, Modifier.size(14.dp), PaperAccentBlue)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val next = reminders.firstOrNull { it.dueAt > System.currentTimeMillis() }
                                DashboardTile("Reminders", next?.title ?: "Nothing coming up", next?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.dueAt)) } ?: "Add a reminder", Glyph.CLOCK, GlassAccent.BLUE, Modifier.weight(1f).heightIn(min = tileHeight)) { nav.navigate("reminders") }
                                DashboardTile("Recent memory", notes.firstOrNull()?.title ?: "Your story starts here", "Open Journal", Glyph.BOOK, GlassAccent.LAVENDER, Modifier.weight(1f).heightIn(min = tileHeight)) { nav.navigate("journal") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DashboardTile("Context library", "Photos and notes", "View all", Glyph.LAYERS, GlassAccent.LAVENDER, Modifier.weight(1f).heightIn(min = tileHeight)) { nav.navigate("library") }
                                DashboardTile("Add a note", "Make this space yours", "Write something", Glyph.PLUS, GlassAccent.BLUSH, Modifier.weight(1f).heightIn(min = tileHeight)) { nav.navigate("note/new") }
                            }
                        }
                    }
                    composable("journal") {
                        Screen("Journal", showDate = true) {
                            Row(Modifier.fillMaxWidth().glassSurface(24).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                MonologIcon(Glyph.CLOCK)
                                Spacer(Modifier.width(12.dp))
                                Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()), color = ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconControl(Glyph.PLUS, "Add note", { nav.navigate("note/new") })
                            }
                            Spacer(Modifier.height(16.dp))
                            if (notes.isEmpty()) EmptyCard("Your story, one day at a time.", "Save a thought from here or the keyboard’s context key.")
                            notes.groupBy { SimpleDateFormat("d MMM · EEEE", Locale.getDefault()).format(Date(it.updatedAt)) }.forEach { (day, entries) ->
                                Text(day, color = muted, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp), fontWeight = FontWeight.Medium)
                                entries.forEach { note ->
                                    NoteTile(note, photosByNote[note.id].orEmpty()) { nav.navigate("note/${note.id}") }
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                    composable("library") {
                        var photosOnly by rememberSaveable { mutableStateOf(false) }
                        Screen("Context library") {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { nav.navigate("note/new") }) { Text("New note") }
                                OutlinedButton(onClick = { nav.navigate("reminders") }) { Text("Reminders") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!photosOnly, { photosOnly = false }, { Text("All entries") })
                                FilterChip(photosOnly, { photosOnly = true }, { Text("With photos") })
                            }
                            Spacer(Modifier.height(8.dp))
                            val entries = if (photosOnly) notes.filter { it.id in photosByNote } else notes
                            if (entries.isEmpty()) {
                                EmptyCard(if (photosOnly) "No photos yet" else "A home for your notes", "Add a photo while writing a note or journal entry.")
                            }
                            entries.forEach { note ->
                                NoteTile(note, photosByNote[note.id].orEmpty()) { nav.navigate("note/${note.id}") }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                    composable("note/new") { NoteEditor(null, emptyList(), onCancel = navigateBack) }
                    composable("note/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        val note = notes.firstOrNull { it.id == entry.arguments!!.getLong("id") }
                        if (note != null) Screen(note.title, onBack = navigateBack) {
                            Text(formatTime(note.updatedAt), color = muted)
                            val photos = photosByNote[note.id].orEmpty()
                            if (photos.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                JournalPhotoStrip(photos.map { JournalPhoto.Saved(it.id) })
                            }
                            if (note.body.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                JournalMarkdown(note.body, Modifier.fillMaxWidth().glassSurface().padding(16.dp))
                            }
                            Spacer(Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { nav.navigate("note/edit/${note.id}") }) { Text("Edit") }
                                OutlinedButton(onClick = { scope.launch { repo.deleteNote(note); nav.popBackStack() } }) { Text("Delete") }
                            }
                        }
                    }
                    composable("note/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        val note = notes.firstOrNull { it.id == entry.arguments!!.getLong("id") }
                        if (note != null) NoteEditor(note, photosByNote[note.id].orEmpty(), onCancel = navigateBack)
                    }
                    composable("reminders") {
                        Screen("Reminders", onBack = navigateBack) {
                            Button(onClick = { nav.navigate("reminders/new") }) { Text("+ Add reminder") }
                            Spacer(Modifier.height(14.dp))
                            if (reminders.isEmpty()) EmptyCard("A little help remembering", "Create a reminder here or from your keyboard.")
                            val (upcoming, past) = reminders.partition { it.dueAt > System.currentTimeMillis() }
                            for ((label, entries) in listOf("Upcoming" to upcoming, "Past" to past.asReversed())) {
                                if (entries.isEmpty()) continue
                                Text(label, color = muted, style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(8.dp))
                                entries.forEach { reminder ->
                                    ReminderTile(
                                        reminder,
                                        onEdit = { nav.navigate("reminders/edit/${reminder.id}") },
                                        onDelete = { scope.launch { repo.deleteReminder(reminder) } }
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    composable("reminders/new") { ReminderEditor(null, onSave = { title, due -> scope.launch { repo.saveReminder(title, due); navigateBack() } }, onCancel = navigateBack) }
                    composable("reminders/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        val reminder = reminders.firstOrNull { it.id == entry.arguments!!.getLong("id") }
                        if (reminder != null) ReminderEditor(reminder, onSave = { title, due -> scope.launch { repo.saveReminder(title, due, reminder); navigateBack() } }, onCancel = navigateBack)
                    }
                    composable("settings") {
                        Screen("Settings", onBack = navigateBack, scrollState = settingsScroll) {
                            Column(Modifier.fillMaxWidth().glassSurface(24, GlassAccent.LAVENDER).padding(16.dp)) {
                                Text("Appearance", color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text("Theme for the companion app and keyboard.", color = muted, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(12.dp))
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
                            Spacer(Modifier.height(16.dp))

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
                                Text("Hold the period key to pause keyboard shortcuts and capture. Monolog automatically protects password fields.", color = muted, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(20.dp))

                            Text("Keyboard setup", color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            SetupScreen(onRequestMicPermission = onRequestMicPermission)
                            Spacer(Modifier.height(20.dp))
                            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear local data") }
                        }
                    }
                }
            }
        }
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
                Row(
                    Modifier.padding(start = 8.dp).glassSurface(16).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MonologIcon(Glyph.SHIELD, Modifier.size(14.dp), muted)
                    Text("On-device", color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                if (onBack == null) IconControl(Glyph.SETTINGS, "Settings", onSettings, frosted = true)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(vertical = 12.dp)) {
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
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
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
private fun NoteTile(note: Note, photos: List<NotePhotoRef>, onClick: () -> Unit) {
    val excerpt = remember(note.body) { markdownExcerpt(note.body).take(150) }
    Row(
        Modifier.fillMaxWidth().glassSurface(20, GlassAccent.LAVENDER).clickable(onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        photos.firstOrNull()?.let {
            JournalPhotoImage(JournalPhoto.Saved(it.id), "Attached photo", Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(note.title, color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (excerpt.isNotEmpty()) {
                Text(excerpt, color = muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (photos.isNotEmpty()) {
                Text(if (photos.size == 1) "1 photo" else "${photos.size} photos", color = PaperAccentBlue, style = MaterialTheme.typography.labelMedium)
            } else if (excerpt.isEmpty()) {
                Text(formatTime(note.updatedAt), color = muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        MonologIcon(Glyph.ARROW, Modifier.size(16.dp), PaperAccentBlue)
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
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial?.body.orEmpty())) }
    var picked by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var removed by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var preview by rememberSaveable { mutableStateOf(false) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!preview, { preview = false }, { Text("Write") })
                FilterChip(preview, {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    preview = true
                }, { Text("Preview") })
            }
            if (preview) {
                if (body.text.isBlank()) Text("Your formatted entry will appear here.", color = muted)
                else JournalMarkdown(body.text, Modifier.fillMaxWidth().padding(4.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { body = formatSelection(body, "\n## ", "", "Heading") }) { Text("Heading") }
                    TextButton(onClick = { body = formatSelection(body, "**", "**", "Bold text") }) { Text("Bold", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { body = formatSelection(body, "\n- ", "", "List item") }) { Text("List") }
                }
                OutlinedTextField(
                    value = body, onValueChange = { body = it }, label = { Text("Write in Markdown") },
                    modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12, shape = RoundedCornerShape(12.dp)
                )
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
                enabled = !saving && (title.isNotBlank() || body.text.isNotBlank() || photos.isNotEmpty()),
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
                            MonologData.repository(context).saveNote(currentTitle, currentBody, initial, NotePhotoChanges(removals, bytes))
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
