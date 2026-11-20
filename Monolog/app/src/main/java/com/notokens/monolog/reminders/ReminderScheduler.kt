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

private const val CHANNEL_ID = "monolog_reminders"
private const val ACTION_FIRE = "com.notokens.monolog.FIRE_REMINDER"

class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(reminder: Reminder) {
        if (reminder.dueAt <= System.currentTimeMillis()) return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.dueAt, pending(reminder.id, reminder.title))
    }

    fun cancel(id: Long) {
        alarms.cancel(pending(id, ""))
    }

    private fun pending(id: Long, title: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra("id", id)
            putExtra("title", title)
        }
        return PendingIntent.getBroadcast(context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val id = intent.getLongExtra("id", 0L)
        val open = PendingIntent.getActivity(context, id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_TODAY
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monolog_m)
            .setContentTitle(intent.getStringExtra("title") ?: "Monolog reminder")
            .setContentText("Your reminder is due")
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(id.toInt(), notification)
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { MonologData.repository(context).rescheduleFutureReminders() }
            finally { pending.finish() }
        }
    }
}
