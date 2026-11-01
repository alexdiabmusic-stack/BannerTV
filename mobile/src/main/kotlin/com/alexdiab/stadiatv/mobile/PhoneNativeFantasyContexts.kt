@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.alexdiab.stadiatv.data.fantasy.*
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.live.StreamAvailabilityStore
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun phoneNativeFantasyScheduleQuery(league: NativeFantasyLeague?, now: Long): PhoneLeagueScheduleQuery? {
    val owner = NativeFantasyContexts.stamp(league, now)?.takeIf { it.entries.isNotEmpty() } ?: return null
    return NativeFantasyFixtures.leaguePath(owner.sport)?.let { PhoneLeagueScheduleQuery(it, Dates.startOfDay(now), 8) }
}

/** One selected-league schedule shared by Home/Live/Guide/roster. Never fetches the full player pool. */
@Composable
internal fun NativeFantasyContextCoordinator(model: MobileViewModel) {
    val library by model.nativeFantasy.state.collectAsStateWithLifecycle()
    val options by model.library.state.collectAsStateWithLifecycle()
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val clock by produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = System.currentTimeMillis(); delay(30_000) }
        }
    }
    LaunchedEffect(clock) { model.updateNativeFantasyClock(clock) }
    val league = NativeFantasyContexts.selected(library)
    val needsSchedule = phoneNeedsFantasySchedule(options.fantasyDisplay, settings.spoilerFreeMode)
    val query = remember(league, Dates.startOfDay(clock), needsSchedule) { if (needsSchedule) phoneNativeFantasyScheduleQuery(league, clock) else null }
    LaunchedEffect(query, connection, lifecycle, settings.hasCompletedOnboarding) {
        if (query != null && settings.hasCompletedOnboarding && (!connection.known || connection.online)) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { model.leagueSchedules.refresh(query); delay(120_000) }
        }
    }
}

/** A pending background result for a previous league/roster must never decorate the newly selected one. */
internal fun phoneNativeFantasyIndex(index: NativeFantasyEventIndex, library: NativeFantasyLibrary, now: Long): NativeFantasyEventIndex =
    index.takeIf { it.owner == NativeFantasyContexts.stamp(NativeFantasyContexts.selected(library), now) } ?: NativeFantasyEventIndex()

@Composable
internal fun NativeFantasyMatchBadge(match: Match, model: MobileViewModel) {
    val library by model.nativeFantasy.state.collectAsStateWithLifecycle()
    val options by model.library.state.collectAsStateWithLifecycle()
    val index by model.nativeFantasyContexts.collectAsStateWithLifecycle()
    if (!options.fantasyDisplay.showLive || match.key in options.hiddenGameKeys) return
    val context = phoneNativeFantasyIndex(index, library, System.currentTimeMillis()).contextFor(match) ?: return
    val count = context.players.size
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (count == 1) "${context.players.single().entry.player.name} · Fantasy roster" else "$count fantasy roster players' teams", Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun NativeFantasyGameContext(match: Match, model: MobileViewModel) {
    val library by model.nativeFantasy.state.collectAsStateWithLifecycle()
    val globalIndex by model.nativeFantasyContexts.collectAsStateWithLifecycle()
    val league = NativeFantasyContexts.selected(library)
    val stamp = remember(league, globalIndex.owner, Dates.startOfDay()) { NativeFantasyContexts.stamp(league, System.currentTimeMillis()) }
    val loaded by produceState<NativeFantasyEventIndex?>(null, league, match, stamp) {
        value = withContext(Dispatchers.Default) { NativeFantasyContexts.build(league, listOf(match), System.currentTimeMillis()) }
    }
    val context = loaded?.takeIf { it.owner == stamp }?.contextFor(match) ?: return
    val sport = stamp?.sport ?: return
    var showAll by rememberSaveable(stamp.leagueId, match.key) { mutableStateOf(false) }
    PhoneCard {
        SectionTitle("Your fantasy roster", "Manage", { model.open(PhonePage.Fantasy) })
        Text("Team fixture context, not confirmed participation or points. Lineup labels are for today only.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        context.players.take(3).forEach { person ->
            NativeFantasyPlayerIdentity(person.entry.player, sport, model)
            Text(person.lineupLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (context.players.size > 3) TextButton(onClick = { showAll = true }) { Text("View all ${context.players.size} rostered players") }
    }
    if (showAll) ModalBottomSheet(onDismissRequest = { showAll = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxHeight(.85f)) { ScreenList {
            item("title") { Text("Your fantasy roster", style = MaterialTheme.typography.titleLarge); Text("${stamp.leagueName} · Team fixture context, not confirmed participation", style = MaterialTheme.typography.bodySmall) }
            items(context.players, key = { it.entry.id }) { person -> PhoneCard {
                NativeFantasyPlayerIdentity(person.entry.player, sport, model)
                Text(person.lineupLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } }
        } }
    }
}

internal data class PhoneFantasyHomeSummary(val live: Boolean, val contexts: List<NativeFantasyEventContext>, val players: Int)

internal fun phoneFantasyHomeCountLabel(summary: PhoneFantasyHomeSummary): String =
    "${summary.players} rostered ${if (summary.players == 1) "player" else "players"} · ${summary.contexts.size} team ${if (summary.contexts.size == 1) "fixture" else "fixtures"}"

/** iOS-style compact summary. Full fixtures and participation caveats belong in the dashboard. */
@Composable
internal fun HomeFantasyContextCard(summary: PhoneFantasyHomeSummary?, fallback: String, model: MobileViewModel) {
    val sources by model.container.streams.sources.collectAsStateWithLifecycle()
    val playlist by model.container.playlists.state.collectAsStateWithLifecycle()
    val guide by model.container.guide.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val liveMatches = summary?.contexts.orEmpty().map { it.match }.filter { it.state == GameState.LIVE }.take(12)
    LaunchedEffect(liveMatches.map { StreamAvailabilityStore.event(it) }, playlist.revision, guide.revision, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            liveMatches.forEach { match ->
                model.container.streams.ensure(match, playlist.channels, { model.container.guide.linkerProgrammes() }, model.container.guide::guideIdFor, guide.revision)
            }
        }
    }
    val watch = liveMatches.firstOrNull { match -> sources[match.key] != null && model.container.streams.sourcesFor(match).confirmed.isNotEmpty() }
    PhoneCard(onClick = { model.open(PhonePage.Fantasy) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Star, null, Modifier.size(24.dp), tint = if (summary?.live == true) BannerLive else MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Overline(if (summary?.live == true) "Fantasy live" else if (summary != null) "Fantasy today" else "Fantasy watch")
                Text(summary?.let(::phoneFantasyHomeCountLabel) ?: fallback, style = MaterialTheme.typography.bodyMedium)
            }
            if (watch != null) Button(onClick = {
                val current = model.container.streams.sourcesFor(watch).confirmed.firstOrNull()
                if (current != null) model.play(current.channel, watch) else model.open(PhonePage.Game(watch))
            }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Text("Watch") }
            else TextButton(onClick = { model.open(PhonePage.Fantasy) }) { Text("View") }
        }
    }
}

internal fun phoneFantasyHomeSummary(index: NativeFantasyEventIndex, hidden: Set<String>, now: Long): PhoneFantasyHomeSummary? {
    val visible = index.contexts.filter { it.match.key !in hidden }
    val live = visible.filter { it.match.state == GameState.LIVE }
    val games = live.ifEmpty { visible.filter { it.match.state == GameState.PRE && Dates.startOfDay(it.match.startMillis) == Dates.startOfDay(now) } }
    if (games.isEmpty()) return null
    return PhoneFantasyHomeSummary(live.isNotEmpty(), games, games.flatMap { it.players }.distinctBy { it.entry.id }.size)
}
