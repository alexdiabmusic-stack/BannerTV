package com.alexdiab.stadiatv.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.data.MatchAlertPlanner
import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.domain.Match

object PhoneNotifications {
    fun sync(context: Context, library: PhoneLibrary, games: List<Match>, settings: UserSettings) {
        val prefs = library.state.value; val now = System.currentTimeMillis()
        val permission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val options = prefs.matchAlerts.copy(enabled = prefs.matchAlerts.enabled && permission)
        val ledger = prefs.notificationLedger.filterValues { now - it <= MatchAlertPlanner.RETENTION }
        val plan = MatchAlertPlanner.plan(games, settings, options, ledger, prefs.reminders.filterNot { it.automatic }.map { it.key }.toSet(), now)
        val desired = plan.map { alert -> PhoneReminder(alert.key, alert.title, alert.game?.league?.path.orEmpty(), alert.game?.id.orEmpty(), alert.game?.startMillis ?: alert.firesAt, alert.firesAt, alert.body, true) }
        val previous = prefs.reminders.filter { it.automatic }
        previous.filter { old -> desired.none { it.key == old.key } }.forEach { PhoneReminders.cancel(context, it.key) }
        desired.forEach { reminder -> if (previous.none { it.key == reminder.key && it.title == reminder.title && it.body == reminder.body && it.firesAt / 60000 == reminder.firesAt / 60000 }) PhoneReminders.schedule(context, reminder) }
        // Stable equality prevents the state observer from scheduling itself forever.
        val retained = previous.filter { old -> desired.any { it.key == old.key && it.title == old.title && it.body == old.body && it.firesAt / 60000 == old.firesAt / 60000 } }
        val final = prefs.reminders.filterNot { it.automatic } + desired.map { new -> retained.firstOrNull { it.key == new.key } ?: new }
        if (final != prefs.reminders || ledger != prefs.notificationLedger) library.update { it.copy(reminders = final, notificationLedger = ledger) }
    }
}

@Composable
fun NotificationSettings(model: MobileViewModel) {
    val prefs by model.library.state.collectAsStateWithLifecycle()
    val options = prefs.matchAlerts
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (allowed) model.library.update { it.copy(matchAlerts = it.matchAlerts.copy(enabled = true)) } else model.message = "Notifications remain off. You can enable them in Android Settings." }
    PhoneCard {
        SettingSwitch("Alerts for followed teams", options.enabled) { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else model.library.update { it.copy(matchAlerts = it.matchAlerts.copy(enabled = enabled)) }
        }
        Text("Reminders are planned seven days ahead. Live and close-game alerts are checked when sports data refreshes; Android can adjust delivery to save battery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (options.enabled) {
            Text("Before kickoff", style = MaterialTheme.typography.labelLarge)
            ChoiceRow(listOf("5 min", "10 min", "15 min", "30 min"), "${options.leadMinutes} min") { value -> model.library.update { it.copy(matchAlerts = it.matchAlerts.copy(leadMinutes = value.substringBefore(' ').toInt())) } }
            SettingSwitch("Game is live", options.live) { value -> model.library.update { it.copy(matchAlerts = it.matchAlerts.copy(live = value)) } }
            SettingSwitch("Close games (off in spoiler-free mode)", options.closeGames) { value -> model.library.update { it.copy(matchAlerts = it.matchAlerts.copy(closeGames = value)) } }
            SettingSwitch("Morning sports briefing", options.digest) { value -> model.library.update { it.copy(matchAlerts = it.matchAlerts.copy(digest = value)) } }
            if (options.digest) ChoiceRow(listOf("7 AM", "8 AM", "9 AM"), "${options.digestHour} AM") { value -> model.library.update { it.copy(matchAlerts = it.matchAlerts.copy(digestHour = value.substringBefore(' ').toInt())) } }
        }
    }
}
