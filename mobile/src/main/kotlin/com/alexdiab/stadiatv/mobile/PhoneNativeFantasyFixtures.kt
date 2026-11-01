package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.alexdiab.stadiatv.data.fantasy.*
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.delay

internal data class PhoneNativeFantasyFixtureContext(
    val day: Long, val snapshot: PhoneSnapshot<List<Match>>, val offline: Boolean,
    val entries: Map<String, List<NativeFantasyFixture>>, val locked: Set<String>, val refresh: () -> Unit,
)

/** Reuses the bounded/coalesced league cache. Only the visible roster requests its own eight-day schedule. */
@Composable
internal fun nativeFantasyFixtureContext(league: NativeFantasyLeague?, model: MobileViewModel, feed: List<Match>, active: Boolean): PhoneNativeFantasyFixtureContext {
    val lifecycle = LocalLifecycleOwner.current
    val clock by produceState(System.currentTimeMillis(), lifecycle, active) {
        if (active) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = System.currentTimeMillis(); delay(30_000) }
        }
    }
    val day = Dates.startOfDay(clock)
    val path = NativeFantasyFixtures.leaguePath(league?.sport.orEmpty())
    val query = path?.let { PhoneLeagueScheduleQuery(it, day, 8) }
    val cached by model.leagueSchedules.state.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val offline = connection.known && !connection.online
    val snapshot = cached[query] ?: PhoneSnapshot()
    LaunchedEffect(query, active, connection, lifecycle) {
        if (query != null && active && !offline) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                model.leagueSchedules.refresh(query)
                delay(120_000)
            }
        }
    }
    val players = league?.userTeam?.roster.orEmpty()
    val matches = remember(snapshot.value, feed) { snapshot.value.orEmpty() + feed }
    val entries = remember(players, league?.sport, matches, day) {
        players.associate { entry -> entry.id to NativeFantasyFixtures.games(entry.player, league?.sport.orEmpty(), matches, clock) }
    }
    val locked = remember(players, league?.sport, matches, day) {
        NativeFantasyFixtures.lockedPlayers(players.map { it.player }, league?.sport.orEmpty(), matches, day)
    }
    return PhoneNativeFantasyFixtureContext(day, snapshot, offline, entries, locked) { if (query != null && !offline) model.leagueSchedules.refresh(query, true) }
}

@Composable
internal fun NativeFantasyFixtureStatus(context: PhoneNativeFantasyFixtureContext) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Your players' fixtures", if (context.offline || context.snapshot.loading) null else "Refresh", context.refresh)
        Text("Eight-day team schedule · no player statistics or fantasy points are inferred from a fixture.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val snapshot = context.snapshot
        Text(when {
            context.offline && snapshot.value != null -> "Offline · Saved schedule ${snapshotTime(snapshot.checkedAt)}"
            context.offline -> "Offline · Available scoreboard fixtures only"
            snapshot.loading -> "Checking team schedule…"
            snapshot.failed && snapshot.value != null -> "Schedule refresh unavailable · Saved ${snapshotTime(snapshot.checkedAt)}"
            snapshot.failed -> "Full schedule unavailable · Available scoreboard fixtures only"
            snapshot.checkedAt > 0 -> "Schedule request completed ${snapshotTime(snapshot.checkedAt)}"
            else -> "Checking team schedule…"
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (snapshot.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeFantasyFixtureInfo(entry: NativeFantasyEntry, context: PhoneNativeFantasyFixtureContext, model: MobileViewModel) {
    val fixtures = context.entries[entry.id].orEmpty()
    val first = fixtures.firstOrNull()
    var showAll by rememberSaveable(entry.id) { mutableStateOf(false) }
    if (first == null) {
        Text(if (context.snapshot.loading) "Checking fixtures…" else if (context.snapshot.value != null)
            "No announced fixture in the saved eight-day schedule." else "Fixture schedule unavailable.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    NativeFantasyFixtureLabel(first)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { model.open(PhonePage.Game(first.match)) }) { Text("Game Centre") }
        if (fixtures.size > 1) TextButton(onClick = { showAll = true }) { Text("${fixtures.size} fixtures") }
    }
    // Shared playlist-EPG linking/playback controls: only confirmed guide evidence gets direct Play.
    // The shared ensure path builds/ranks off-main and coalesces the index for teammates' same fixture.
    if (first.match.state != GameState.FINAL) MatchWatchBar(first.match, model)
    if (showAll) ModalBottomSheet(onDismissRequest = { showAll = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxHeight(.85f)) { ScreenList {
            item("title") { Text(entry.player.name, style = MaterialTheme.typography.titleLarge); Text("Announced team fixtures · doubleheaders remain separate", style = MaterialTheme.typography.bodySmall) }
            items(fixtures, key = { it.match.key }) { fixture -> PhoneCard(onClick = { showAll = false; model.open(PhonePage.Game(fixture.match)) }) {
                NativeFantasyFixtureLabel(fixture)
                Text("Open Game Centre", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            } }
        } }
    }
}

@Composable
private fun NativeFantasyFixtureLabel(fixture: NativeFantasyFixture) {
    val match = fixture.match
    Text("${if (fixture.home) "vs" else "at"} ${fixture.opponent.displayName}", style = MaterialTheme.typography.titleSmall)
    Text("${Dates.format("EEE, MMM d, yyyy", match.startMillis)} · ${clockTime(match.startMillis)}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(when (match.state) { GameState.LIVE -> "Reported live · ${match.statusDetail}"; GameState.FINAL -> "Final"; GameState.PRE -> match.statusDetail.ifBlank { "Upcoming" } },
        style = MaterialTheme.typography.labelMedium, color = if (match.state == GameState.LIVE) BannerLive else MaterialTheme.colorScheme.onSurfaceVariant)
}
