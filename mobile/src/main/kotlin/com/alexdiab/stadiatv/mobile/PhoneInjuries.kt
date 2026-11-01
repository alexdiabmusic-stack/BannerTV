package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.League

/** Long league reports are individual lazy rows, never one eagerly composed mega-card. */
@Composable
fun PhoneInjuriesScreen(league: League, model: MobileViewModel) {
    val billing by model.entitlements.state.collectAsStateWithLifecycle()
    if (!billing.isPremium) { PhonePremiumGate("Injury report", model); return }
    val snapshots by model.leagueInjuries.state.collectAsStateWithLifecycle()
    LaunchedEffect(league.path) { model.leagueInjuries.refresh(league.path) }
    val snapshot = snapshots[league.path] ?: PhoneSnapshot()
    val injuries = snapshot.value.orEmpty()
    val source = injuries.mapNotNull { it.source }.distinct().joinToString(" / ")
    ScreenList {
        item("status") { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(source.takeIf(String::isNotBlank), when {
                snapshot.loading -> "Updating injury report…"
                snapshot.failed && injuries.isNotEmpty() -> "Refresh unavailable · last report retained"
                snapshot.failed -> "Injury report unavailable"
                snapshot.checkedAt > 0 -> "Checked ${snapshotTime(snapshot.checkedAt)}"
                else -> "Injury report"
            }).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { model.leagueInjuries.refresh(league.path, true) }, enabled = !snapshot.loading) { Text(if (snapshot.failed) "Retry" else "Refresh") }
        } }
        if (snapshot.loading && injuries.isEmpty()) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!snapshot.loading && injuries.isEmpty()) item("empty") { EmptyState(if (snapshot.failed) "Report unavailable" else "No injuries listed",
            if (snapshot.failed) "The report couldn't load. Retry without losing any previously loaded report." else "No current injuries were returned by the provider.") }
        itemsIndexed(injuries, key = { index, injury -> "${injury.athlete.id}:${injury.team}:$index" }) { _, injury ->
            PhoneCard(onClick = { model.open(PhonePage.PlayerDetail(league, injury.athlete)) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(injury.athlete.headshotUrl, injury.athlete.name, 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(injury.athlete.name, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(injury.team, injury.athlete.position).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(injury.status, color = BannerLive, style = MaterialTheme.typography.titleSmall)
                if (injury.detail.isNotBlank()) Text(injury.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
