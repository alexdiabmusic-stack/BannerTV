package com.alexdiab.stadiatv.mobile

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match

private const val CHANNEL_ID = "game-reminders"

fun reminderTime(startsAt: Long, now: Long): Long? = if (startsAt <= now) null else maxOf(now + 1_000L, startsAt - 10 * 60_000L)

object PhoneReminders {
    fun schedule(context: Context, reminder: PhoneReminder) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.firesAt, pending(context, reminder.key))
    }
    fun cancel(context: Context, key: String) { context.getSystemService(AlarmManager::class.java).cancel(pending(context, key)) }
    private fun pending(context: Context, key: String) = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setData(Uri.Builder().scheme("banner-reminder").authority("game").appendPath(key).build()).putExtra("key", key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val library = PhoneLibrary(context)
        val reminder = library.state.value.reminders.firstOrNull { it.key == intent.getStringExtra("key") } ?: return
        if (reminder.automatic && !library.state.value.matchAlerts.enabled) return
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Game reminders", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val uri = if (reminder.gameId.isEmpty()) Uri.parse("banner://following") else Uri.Builder().scheme("banner").authority("game").appendQueryParameter("id", reminder.gameId).appendQueryParameter("league", reminder.league).appendQueryParameter("date", reminder.startsAt.toString()).build()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MobileActivity::class.java).setData(uri).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title).setContentText(reminder.body ?: "Starts at ${clockTime(reminder.startsAt)}")
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_EVENT).build()
        NotificationManagerCompat.from(context).notify(reminder.key.hashCode(), notification)
        library.update { it.copy(reminders = it.reminders.filterNot { r -> r.key == reminder.key }, notificationLedger = it.notificationLedger + (reminder.key to System.currentTimeMillis())) }
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val library = PhoneLibrary(context)
        val future = library.state.value.reminders.filter { it.startsAt > System.currentTimeMillis() }
        future.forEach { PhoneReminders.schedule(context, it.copy(firesAt = maxOf(System.currentTimeMillis() + 1_000L, it.firesAt))) }
        library.update { it.copy(reminders = future) }
    }
}

@Composable
fun GameReminderButton(match: Match, model: MobileViewModel) {
    val context = LocalContext.current
    val library by model.library.state.collectAsStateWithLifecycle()
    val isSet = library.reminders.any { it.key == match.key }
    fun toggle() {
        if (isSet) {
            PhoneReminders.cancel(context, match.key)
            model.library.update { it.copy(reminders = it.reminders.filterNot { r -> r.key == match.key }) }
        } else {
            val whenTo = reminderTime(match.startMillis, System.currentTimeMillis()) ?: return
            val reminder = PhoneReminder(match.key, match.name, match.league.path, match.id, match.startMillis, whenTo)
            PhoneReminders.schedule(context, reminder)
            model.library.update { it.copy(reminders = it.reminders.filterNot { r -> r.key == match.key } + reminder) }
            model.message = "Reminder set for ${clockTime(whenTo)}. Android may adjust delivery time to save battery."
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) toggle() else model.message = "Notifications are disabled. You can enable them in Android Settings." }
    OutlinedButton(onClick = {
        if (!isSet && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else toggle()
    }, enabled = match.state == GameState.PRE) { Icon(Icons.Rounded.Notifications, null, Modifier.size(18.dp)); Text(if (isSet) "Reminder set" else "Remind me") }
}
