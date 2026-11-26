package com.notokens.monolog.reminders

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import com.notokens.monolog.data.Reminder
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ReminderCalendar(private val context: Context) {
    data class Choice(val id: Long, val name: String, val account: String?)

    private val preferences = context.getSharedPreferences("reminder_calendar", Context.MODE_PRIVATE)
    var selectedId: Long
        get() = preferences.getLong("calendar_id", -1L)
        set(value) { preferences.edit().putLong("calendar_id", value).apply() }

    fun hasAccess() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun choices(): List<Choice> = provider(emptyList()) {
        context.contentResolver.query(Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), "${Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val account = rows.getString(2).takeUnless { rows.getString(3) == ACCOUNT_TYPE_LOCAL }
                    add(Choice(rows.getLong(0), rows.getString(1) ?: "Calendar", account))
                }
            }
        }.orEmpty()
    }

    suspend fun save(reminder: Reminder): Boolean {
        val target = selectedId.takeIf { it >= 0L }?.let { id -> choices().firstOrNull { it.id == id } }
        return provider(selectedId < 0L) {
            val link = eventLink(reminder.id, create = target != null) ?: return@provider selectedId < 0L
            val selection = "${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.CUSTOM_APP_URI} = ? AND ${Events.DELETED} = 0"
            val args = arrayOf(context.packageName, link)
            val existing = context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID), selection, args, null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else null }
            if (existing == null && target == null) return@provider selectedId < 0L
            val values = ContentValues().apply {
                put(Events.TITLE, reminder.title)
                put(Events.DTSTART, reminder.dueAt)
                put(Events.DTEND, reminder.dueAt + 60_000)
                put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            }
            if (existing != null) {
                val uri = ContentUris.withAppendedId(Events.CONTENT_URI, existing)
                context.contentResolver.update(uri, values, null, null) == 1
            } else {
                values.put(Events.CALENDAR_ID, checkNotNull(target).id)
                values.put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
                values.put(Events.CUSTOM_APP_PACKAGE, context.packageName)
                values.put(Events.CUSTOM_APP_URI, link)
                context.contentResolver.insert(Events.CONTENT_URI, values) != null
            }
        }
    }

    suspend fun remove(id: Long) = provider(Unit) {
        val link = eventLink(id, create = false) ?: return@provider
        val resolver = context.contentResolver
        val ids = resolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.CUSTOM_APP_URI} = ? AND ${Events.DELETED} = 0",
            arrayOf(context.packageName, link), null)?.use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getLong(0)) }
        }.orEmpty()
        ids.forEach { resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, it), null, null) }
    }

    private fun eventLink(id: Long, create: Boolean): String? {
        val token = preferences.getString("install_id", null) ?: if (create) {
            UUID.randomUUID().toString().also { preferences.edit().putString("install_id", it).commit() }
        } else return null
        return "monolog://reminder/$token/$id"
    }

    private suspend fun <T> provider(fallback: T, action: () -> T): T = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext fallback
        try { action() }
        catch (error: RuntimeException) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            android.util.Log.w("MonologReminder", "Calendar provider unavailable: ${error.javaClass.simpleName}")
            fallback
        }
    }

    companion object {
        val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}
