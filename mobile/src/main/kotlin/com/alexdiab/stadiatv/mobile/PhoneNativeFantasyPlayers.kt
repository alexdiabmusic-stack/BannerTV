package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.alexdiab.stadiatv.data.fantasy.*
import com.alexdiab.stadiatv.domain.League
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class PhoneFantasyPlayerPage<T>(val rows: List<T>, val index: Int, val pages: Int, val first: Int, val last: Int)

internal fun phoneFantasyAvailablePlayers(players: List<NativeFantasyPlayer>, used: Set<String>, query: String, position: String?, team: String?): List<NativeFantasyPlayer> {
    val text = query.trim()
    return players.filter { it.id !in used && (position == null || it.position == position) && (team == null || it.team == team) &&
        (text.isEmpty() || "${it.name} ${it.team} ${it.position}".contains(text, true)) }
}

/** This screen is inside one Following LazyColumn item: never accumulate every pool row in its Column. */
internal fun <T> phoneFantasyPlayerPage(players: List<T>, requested: Int): PhoneFantasyPlayerPage<T> {
    val pages = if (players.isEmpty()) 0 else (players.size - 1) / 30 + 1
    val index = requested.coerceIn(0, (pages - 1).coerceAtLeast(0))
    val first = index * 30
    val last = (first + 30).coerceAtMost(players.size)
    return PhoneFantasyPlayerPage(players.subList(first, last), index, pages, if (players.isEmpty()) 0 else first + 1, last)
}

@Composable
internal fun NativeFantasyPlayers(
    league: NativeFantasyLeague, model: MobileViewModel, query: String, search: (String) -> Unit,
    requestedPage: Int, selectPage: (Int) -> Unit, dropForAdd: String?, cancelDrop: () -> Unit,
    pick: (NativeFantasyPlayer, List<NativeFantasyPlayer>) -> Unit,
) {
    val policy = remember(league) { runCatching { NativeFantasyPoolPolicy.key(league) } }
    val key = policy.getOrNull()
    val cached by model.nativeFantasyPools.state.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val offline = connection.known && !connection.online
    val snapshot = cached[key] ?: PhoneSnapshot()
    val pool = snapshot.value
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(key, offline, lifecycle) {
        if (key != null && !offline) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { model.nativeFantasyPools.refresh(key); delay(60_000) }
        }
    }
    if (key == null) {
        Text(policy.exceptionOrNull()?.message ?: "Player identities could not be checked. Saved drafts are unchanged.", color = MaterialTheme.colorScheme.error)
        return
    }
    val all = pool?.players.orEmpty()
    var position by rememberSaveable(league.id) { mutableStateOf<String?>(null) }
    var team by rememberSaveable(league.id) { mutableStateOf<String?>(null) }
    val positions = remember(all) { all.map { it.position }.filter(String::isNotBlank).distinct().sorted() }
    val teams = remember(all) { all.map { it.team }.filter(String::isNotBlank).distinct().sorted() }
    LaunchedEffect(all) {
        if (all.isNotEmpty()) {
            if (position != null && position !in positions) position = null
            if (team != null && team !in teams) team = null
        }
    }
    val used = remember(league) { league.teams.flatMap { it.roster }.map { it.player.id }.toSet() }
    val available = remember(all, used, query, position, team) { phoneFantasyAvailablePlayers(all, used, query, position, team) }
    val page = phoneFantasyPlayerPage(available, requestedPage)
    val header = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    fun navigate(index: Int) { scope.launch {
        selectPage(index)
        withFrameNanos { } // Let the new page lay out before requesting its small search header, not the entire tall column.
        header.bringIntoView()
    } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.bringIntoViewRequester(header), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (key.source == NativeFantasyPoolSource.Native) "${key.sport.uppercase()} · Native roster directory" else "ESPN roster directory · Original draft identities",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    offline && pool != null -> "Offline · Retained pool ${snapshotTime(pool.loadedAt)}"
                    offline -> "Offline · Connect to load the player directory"
                    snapshot.loading -> "Checking team rosters…"
                    pool != null && !pool.complete && !pool.retainedPrevious -> "Directory incomplete · Loaded ${snapshotTime(pool.loadedAt)}"
                    snapshot.failed && pool != null -> "Refresh unavailable · Retained pool ${snapshotTime(pool.loadedAt)}"
                    snapshot.failed -> "Player directory unavailable. Saved rosters are unchanged."
                    pool != null -> "Roster request completed ${snapshotTime(pool.loadedAt)}"
                    else -> "Checking player directory…"
                }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = !offline && !snapshot.loading, onClick = { model.nativeFantasyPools.refresh(key, true) }) { Text(if (snapshot.failed) "Retry" else "Refresh") }
            }
            if (pool != null && !pool.complete) Text(buildString {
                append(if (pool.retainedPrevious) "Incomplete refresh · previous player pool retained." else "Partial player pool.")
                if (pool.unavailableTeams.isNotEmpty()) append(" ${pool.unavailableTeams.size} team rosters unavailable.")
                if (pool.ambiguousPlayers > 0) append(" ${pool.ambiguousPlayers} conflicting club assignments excluded.")
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(query, search, Modifier.fillMaxWidth(), label = { Text("Search player, team or position") }, singleLine = true)
            if (positions.isNotEmpty()) ChoiceRow(listOf("All positions") + positions, position ?: "All positions") {
                position = it.takeUnless { it == "All positions" }; selectPage(0)
            }
            if (teams.isNotEmpty()) ChoiceRow(listOf("All teams") + teams, team ?: "All teams") {
                team = it.takeUnless { it == "All teams" }; selectPage(0)
            }
            if (page.pages > 0) Text("${page.first}–${page.last} of ${available.size} available · Page ${page.index + 1} of ${page.pages}", style = MaterialTheme.typography.labelSmall)
        }
        if (dropForAdd != null) AssistChip(onClick = cancelDrop, label = { Text("Drop ${league.userTeam.roster.firstOrNull { it.id == dropForAdd }?.player?.name ?: "player"} when adding · cancel") })
        page.rows.forEach { player -> androidx.compose.runtime.key(player.id) { PhoneCard {
            NativeFantasyPlayerIdentity(player, league.sport, model)
            TextButton(enabled = league.nextPick?.team == league.userTeam.id || league.nextPick == null, onClick = { pick(player, all) }) { Text(if (league.nextPick != null) "Draft player" else "Add player") }
        } } }
        if (page.pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = page.index > 0, onClick = { navigate(page.index - 1) }, modifier = Modifier.weight(1f)) { Text("Previous") }
            OutlinedButton(enabled = page.index + 1 < page.pages, onClick = { navigate(page.index + 1) }, modifier = Modifier.weight(1f)) { Text("Next") }
        }
        if (available.isEmpty() && !snapshot.loading && pool != null) EmptyState("No available players", if (pool.complete) "Try another name or position." else "Some team rosters could not be checked. Try another search or refresh the directory.")
        Text("Player profiles and reported roster information are available. Verified fantasy-point totals are not connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun NativeFantasyPlayerIdentity(player: NativeFantasyPlayer, sport: String, model: MobileViewModel) {
    val athlete = remember(player, sport) { NativeFantasyPoolPolicy.athlete(player, sport) }
    val league = NativeFantasyFixtures.leaguePath(sport)?.let(League::find)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = athlete != null && league != null, onClickLabel = "Open player profile") {
        if (athlete != null && league != null) model.open(PhonePage.PlayerDetail(league, athlete))
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Logo(player.image, player.name, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(player.name, fontWeight = FontWeight.Bold)
            Text("${player.team} · ${player.position}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Eligible: ${player.eligible.joinToString(" / ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            player.injury?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = BannerLive) }
        }
        if (athlete != null && league != null) Icon(Icons.Rounded.ChevronRight, "Player details")
    }
}
