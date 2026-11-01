package com.alexdiab.stadiatv.mobile

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.data.fantasy.*
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.*

class PhoneNativeFantasyStore(context: Context) {
    private val prefs = context.getSharedPreferences("native-fantasy", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching { AppJson.decodeFromString<NativeFantasyLibrary>(prefs.getString("library", "{}")!!) }.getOrDefault(NativeFantasyLibrary()))
    val state = mutable.asStateFlow()
    @Synchronized fun update(transform: (NativeFantasyLibrary) -> NativeFantasyLibrary) {
        val next = transform(mutable.value)
        require(next.leagues.size <= 20) { "You can keep up to 20 local leagues." }
        val encoded = AppJson.encodeToString(next)
        prefs.edit().putString("library", encoded).apply(); mutable.value = next
    }
    fun change(id: String, transform: (NativeFantasyLeague) -> NativeFantasyLeague) = update { library -> library.copy(leagues = library.leagues.map { if (it.id == id) transform(it) else it }) }
    fun create(league: NativeFantasyLeague) = update { it.copy(selected = league.id, leagues = it.leagues + league) }
}

@Composable
fun NativeFantasyContent(model: MobileViewModel) {
    val library by model.nativeFantasy.state.collectAsStateWithLifecycle()
    val feed by model.container.matchFeed.sections.collectAsStateWithLifecycle()
    val league = library.leagues.firstOrNull { it.id == library.selected } ?: library.leagues.firstOrNull()
    var creating by rememberSaveable { mutableStateOf(false) }
    var pendingDraftLeagueId by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable(league?.id) { mutableStateOf("Roster") }
    LaunchedEffect(league?.id, pendingDraftLeagueId) {
        if (league?.id != null && league.id == pendingDraftLeagueId) {
            section = "Players"
            pendingDraftLeagueId = null
        }
    }
    var query by rememberSaveable(league?.id) { mutableStateOf("") }
    var error by remember(league?.id) { mutableStateOf<String?>(null) }
    var page by rememberSaveable(league?.id, query) { mutableIntStateOf(0) }
    var pendingDrop by remember(league?.id) { mutableStateOf<NativeFantasyEntry?>(null) }
    var dropForAdd by rememberSaveable(league?.id) { mutableStateOf<String?>(null) }
    val fixtures = nativeFantasyFixtureContext(league, model, feed.all, section == "Roster" && league?.userTeam?.roster?.isNotEmpty() == true)
    fun change(block: (NativeFantasyLeague) -> NativeFantasyLeague) { val selected = league ?: return; try { model.nativeFantasy.change(selected.id, block); error = null } catch (known: IllegalArgumentException) { error = known.message } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row { Text("Banner Fantasy", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall); TextButton(onClick = { creating = true }) { Text("Create") } }
        Text("Personal teams and simulated leagues stay on this device. CPU teams are explicitly simulated; no remote multiplayer service is connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (library.leagues.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(library.leagues, key = { it.id }) { saved -> FilterChip(selected = saved.id == league?.id,
                onClick = { model.nativeFantasy.update { it.copy(selected = saved.id) } }, label = { Text(saved.name) }) }
        }
        if (league == null) EmptyState("Build your own fantasy team", "Choose NFL, NHL, NBA or MLB, customize your roster and scoring, and draft a personal team or compete in a local simulated league.", "Create a league", { creating = true })
        else {
            PhoneCard { Row { Text(league.name, Modifier.weight(1f), fontWeight = FontWeight.Bold); StatusPill(league.sport.uppercase()) }; Text("${league.userTeam.name} · ${league.phase} · ${league.scoringType}"); league.nextPick?.let { pick -> Text("Round ${pick.round} · Pick ${pick.number} · ${league.teams.first { it.id == pick.team }.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) } }
            ChoiceRow(listOf("Roster", "Players", "Draft board", "Scoring", "Transactions"), section) { section = it }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            when (section) {
                "Players" -> {
                    NativeFantasyPlayers(league, model, query, { query = it }, page, { page = it }, dropForAdd, { dropForAdd = null }) { player, pool ->
                        change { if (it.nextPick != null) NativeFantasyEngine.draft(it, player, pool) else NativeFantasyEngine.add(it, player, dropForAdd) }; dropForAdd = null
                    }
                }
                "Draft board" -> league.picks.forEach { pick -> ListItem(headlineContent = { Text("${pick.number}. ${pick.name ?: "Waiting for pick"}") }, supportingContent = { Text("R${pick.round} · ${league.teams.first { it.id == pick.team }.name}") }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) }
                "Scoring" -> {
                    StatTable("Roster configuration", listOf("Slot", "Count"), league.slots.filterValues { it > 0 }.map { listOf(it.key, it.value.toString()) })
                    StatTable(league.scoringType, listOf("Statistic", if (league.scoringType == "Points") "Weight" else "Category"), league.points.map { listOf(fantasyStatLabel(it.key), if (league.scoringType == "Points") it.value.toString() else if (it.key in league.categories) "Included" else "Off") })
                    Text("Unavailable live statistics are shown as unavailable, not as zero. This local league has no verified live scoring feed yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "Transactions" -> { if (league.transactions.isEmpty()) Text("No transactions yet."); league.transactions.take(100).forEach { tx -> PhoneCard { Text(tx.text); Text("${league.teams.firstOrNull { it.id == tx.team }?.name.orEmpty()} · ${dateLabel(tx.time)} ${clockTime(tx.time)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
                else -> {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(fixtures.day))
                    val assignments = league.lineups[day].orEmpty()
                    val locked = fixtures.locked
                    if (league.userTeam.roster.isEmpty()) EmptyState("Your draft starts here", "Choose players to build your roster.", "Open player pool", { section = "Players" })
                    else {
                        if (league.nextPick != null) OutlinedButton(onClick = { section = "Players" }, Modifier.fillMaxWidth()) { Text("Continue draft") }
                        NativeFantasyFixtureStatus(fixtures)
                    }
                    league.userTeam.roster.forEach { entry -> PhoneCard {
                        NativeFantasyPlayerIdentity(entry.player, league.sport, model)
                        if (entry.player.id in locked) StatusPill("Lineup locked · game started")
                        ChoiceRow(league.slots.filterValues { it > 0 }.keys.filter { it == "BN" || it in entry.player.eligible || it in setOf("IR", "IL") && entry.player.injury != null }, assignments[entry.id].orEmpty(), enabled = entry.player.id !in locked) { slot -> change { NativeFantasyEngine.move(it, day, entry.id, slot, locked) } }
                        Row { TextButton(onClick = { pendingDrop = entry }) { Text("Drop") }; TextButton(onClick = { dropForAdd = entry.id; section = "Players" }) { Text("Replace") } }
                        HorizontalDivider()
                        NativeFantasyFixtureInfo(entry, fixtures, model)
                    } }
                    if (!league.personal) { SectionTitle("Simulated teams"); league.teams.filter { it.cpu }.forEach { cpu -> Text("${cpu.name} · ${cpu.roster.size} drafted", style = MaterialTheme.typography.bodyMedium) } }
                }
            }
        }
    }
    if (creating) NativeLeagueBuilder({ creating = false }) { created -> try {
        model.nativeFantasy.create(created)
        pendingDraftLeagueId = created.id
        creating = false
    } catch (known: IllegalArgumentException) { error = known.message; creating = false } }
    pendingDrop?.let { entry -> AlertDialog(onDismissRequest = { pendingDrop = null }, title = { Text("Drop ${entry.player.name}?") }, text = { Text("This removes the player from your local roster and saved lineups.") }, confirmButton = { TextButton(onClick = { change { NativeFantasyEngine.drop(it, entry.id) }; pendingDrop = null }) { Text("Drop player") } }, dismissButton = { TextButton(onClick = { pendingDrop = null }) { Text("Cancel") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeLeagueBuilder(close: () -> Unit, create: (NativeFantasyLeague) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }; var team by rememberSaveable { mutableStateOf("") }
    var sport by rememberSaveable { mutableStateOf("nhl") }; var mode by rememberSaveable { mutableStateOf("Personal team") }; var scoring by rememberSaveable { mutableStateOf("Points") }; var count by rememberSaveable { mutableStateOf("8") }
    var slots by remember(sport) { mutableStateOf(NativeFantasyEngine.configurableSlots(sport)) }; var rules by remember(sport) { mutableStateOf(NativeFantasyEngine.points(sport).mapValues { it.value.toString() }) }; var categories by remember(sport) { mutableStateOf(NativeFantasyEngine.points(sport).keys) }
    var custom by rememberSaveable { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { Box(Modifier.fillMaxHeight(.94f)) { ScreenList {
        item { Text("Create Banner Fantasy", style = MaterialTheme.typography.headlineSmall) }
        item { PhoneField("League name", name, { name = it }); PhoneField("Your team name", team, { team = it }) }
        item { ChoiceRow(NativeFantasyEngine.sports.map { it.uppercase() }, sport.uppercase()) { sport = it.lowercase() }; ChoiceRow(listOf("Personal team", "Simulated league"), mode) { mode = it }; if (mode == "Simulated league") PhoneField("Teams (2–12)", count, { count = it }, KeyboardType.Number) }
        item { ChoiceRow(listOf("Points", "Categories", "Rotisserie"), scoring) { scoring = it }; TextButton(onClick = { custom = !custom }) { Text(if (custom) "Hide customization" else "Customize roster & scoring") } }
        if (custom) {
            item { SectionTitle("Roster slots") }
            items(slots.keys.toList(), key = { "slot-$it" }) { slot -> Row { Text(slot, Modifier.weight(1f).padding(top = 14.dp)); IconButton(onClick = { slots = slots + (slot to ((slots[slot] ?: 0) - 1).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Remove, "Remove $slot slot") }; Text("${slots[slot]}", Modifier.padding(top = 14.dp)); IconButton(onClick = { slots = slots + (slot to ((slots[slot] ?: 0) + 1).coerceAtMost(20)) }) { Icon(Icons.Rounded.Add, "Add $slot slot") } } }
            item { SectionTitle("Scoring rules") }
            items(rules.keys.toList(), key = { "rule-$it" }) { stat -> if (scoring == "Points") OutlinedTextField(rules[stat].orEmpty(), { value -> rules = rules + (stat to value) }, Modifier.fillMaxWidth(), label = { Text(fantasyStatLabel(stat)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true) else SettingSwitch(fantasyStatLabel(stat), stat in categories) { enabled -> categories = if (enabled) categories + stat else categories - stat } }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { Button(onClick = { try { val values = rules.mapValues { requireNotNull(it.value.toDoubleOrNull()) { "Enter a number for ${fantasyStatLabel(it.key)}." } }; create(NativeFantasyEngine.create(name, team, sport, mode == "Personal team", count.toIntOrNull() ?: 8, slots, scoring, values, categories)) } catch (known: IllegalArgumentException) { error = known.message } }, Modifier.fillMaxWidth()) { Text("Create & start draft") } }
    } } }
}
private fun fantasyStatLabel(raw: String) = raw.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
