package com.alexdiab.stadiatv.mobile

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PhonePremiumScreen(model: MobileViewModel, activity: Activity) {
    val state by model.entitlements.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val offer = state.offers.firstOrNull { it.key == selected }
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.entitlements.refresh() }
    ScreenList {
        item("hero") { PhoneCard {
            Icon(if (state.isPremium) Icons.Rounded.Verified else Icons.Rounded.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text(if (state.unlockedBuild) "Every feature, unlocked." else if (state.entitled) "Premium is active." else "More of every game.", style = MaterialTheme.typography.headlineMedium)
            Text(when { state.unlockedBuild -> "All features are included in this development build. Purchases are disabled; no payment is required or will be started."
                !state.configured -> "Google Play products and purchase verification are not configured for this build."
                state.entitled -> "Access was verified for your current Google Play purchase."
                else -> "Choose an available Google Play offer. Prices and any trial terms come directly from Google Play." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item("features") { PhoneCard {
            Text("BANNER PREMIUM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            listOf("Game Centre" to "Rosters, leaders and deep team statistics", "Play by play" to "Reported key moments and scoring timelines", "Multiview" to "Multiple channels, within your device's limits", "Full standings" to "Conference, division and competition tables", "Injury reports" to "Published player availability", "Smart alerts" to "Game reminders and close-game notifications", "Morning briefing" to "A daily view of the games you follow").forEach { (title, detail) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text("Sports data depends on provider availability. Television requires your own authorized playlist; Premium does not include channels.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (state.loading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.notice?.let { notice -> item("notice") { PhoneCard { Text(notice, style = MaterialTheme.typography.bodyMedium) } } }
        if (state.configured && !state.unlockedBuild && !state.entitled) {
            if (state.offers.isEmpty() && !state.loading) item("empty") { EmptyState("Offers unavailable", "No purchasable offers were returned for this account. Restore still checks existing purchases.", "Refresh", { model.entitlements.refresh() }) }
            state.offers.forEach { available -> item("offer:${available.key}") { PhoneCard(onClick = { selected = available.key }) {
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == available.key, { selected = available.key }); Text(available.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium) }
                if (available.description.isNotBlank()) Text(available.description, style = MaterialTheme.typography.bodySmall)
                available.terms.forEachIndexed { index, terms -> Text("${if (index > 0) "Then " else ""}$terms", style = MaterialTheme.typography.bodyMedium) }
            } } }
            item("buy") { Button(onClick = { offer?.let { model.entitlements.purchase(activity, it.key) } }, Modifier.fillMaxWidth(), enabled = offer != null && !state.loading && !state.checkoutActive && !state.pending) {
                Text(if (state.checkoutActive) "Waiting for Google Play…" else if (state.pending) "Payment pending" else "Continue with Google Play")
            } }
            item("terms") { Text("Review the displayed offer and Google Play confirmation before buying. Recurring offers renew as shown until cancelled in Google Play. Cancelling renewal preserves access until the paid period ends. One-time purchases do not renew.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (state.configured && !state.unlockedBuild) item("restore") {
            OutlinedButton(onClick = { model.entitlements.refresh(restore = true) }, Modifier.fillMaxWidth(), enabled = !state.loading && !state.checkoutActive) { Text("Restore purchases") }
        }
        if (state.configured && !state.unlockedBuild) item("manage") { TextButton(onClick = { openBrowser(context, "https://play.google.com/store/account/subscriptions?package=${context.packageName}") }, Modifier.fillMaxWidth()) { Text("Manage Google Play subscriptions") } }
    }
}

@Composable
fun PhonePremiumGate(title: String, model: MobileViewModel) {
    EmptyState(title, "This section is included with Banner Premium. View available offers or restore an existing purchase.", "View Premium", { model.open(PhonePage.Premium) })
}
