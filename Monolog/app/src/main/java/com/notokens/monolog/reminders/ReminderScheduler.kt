package com.notokens.monolog.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.notokens.monolog.MainActivity
import com.notokens.monolog.R
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.Reminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "monolog_reminder_alarms"
private const val ACTION_FIRE = "com.notokens.monolog.FIRE_REMINDER"

class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    val exactEnabled: Boolean get() = alarms.canScheduleExactAlarms()

    fun schedule(reminder: Reminder) {
        if (reminder.dueAt <= System.currentTimeMillis()) return
        val operation = pending(reminder.id, reminder.dueAt)
        if (exactEnabled) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.dueAt, operation)
                return
            } catch (_: SecurityException) { /* Access may have been revoked. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.dueAt, operation)
    }

    fun cancel(id: Long) {
        alarms.cancel(pending(id, 0L))
        NotificationManagerCompat.from(context).cancel(id.toInt())
    }

    private fun pending(id: Long, dueAt: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra("id", id)
            putExtra("dueAt", dueAt)
        }
        return PendingIntent.getBroadcast(context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra("id", 0L)
        val dueAt = intent.getLongExtra("dueAt", 0L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reminder = com.notokens.monolog.data.MonologDatabase.get(context).reminders().current(id) ?: return@launch
                if ((dueAt != 0L && dueAt != reminder.dueAt) || reminder.dueAt > System.currentTimeMillis()) return@launch
                notifyReminder(context, reminder)
            } finally { pending.finish() }
        }
    }

    private fun notifyReminder(context: Context, reminder: Reminder) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = reminder.id
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reminder alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        })
        val open = PendingIntent.getActivity(context, id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_TODAY
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monolog_m)
            .setContentTitle(reminder.title)
            .setContentText("Your reminder is due")
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        NotificationManagerCompat.from(context).notify(id.toInt(), notification)
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { MonologData.repository(context).rescheduleFutureReminders() }
            finally { pending.finish() }
        }
    }
}
