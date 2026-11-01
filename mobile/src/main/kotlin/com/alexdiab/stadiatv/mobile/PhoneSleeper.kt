package com.alexdiab.stadiatv.mobile

import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.data.fantasy.*
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File

/** The large public player directory is streamed from disk and filtered, never parsed into one huge object. */
private suspend fun sleeperPlayers(model: MobileViewModel, ids: Set<String>): Map<String, SleeperPlayer> = withContext(Dispatchers.IO) {
    if (ids.isEmpty()) return@withContext emptyMap()
    val app = model.getApplication<android.app.Application>()
    val directory = File(app.cacheDir, "sleeper-nfl-directory.json")
    if (!directory.exists() || System.currentTimeMillis() - directory.lastModified() > 86400000) {
        val atomic = android.util.AtomicFile(directory)
        val output = atomic.startWrite()
        try {
            model.container.http.client.newCall(Request.Builder().url("https://api.sleeper.app/v1/players/nfl").build()).execute().use { response ->
                require(response.isSuccessful) { "Sleeper's player directory is unavailable." }
                response.body.byteStream().use { input -> val chunk = ByteArray(32768); var total = 0L; while (true) { currentCoroutineContext().ensureActive(); val count = input.read(chunk); if (count < 0) break; total += count; require(total <= 64 * 1024 * 1024) { "Player directory exceeds safe size." }; output.write(chunk, 0, count) } }
            }
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    val players = mutableMapOf<String, SleeperPlayer>()
    JsonReader(directory.reader()).use { reader ->
        reader.beginObject()
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            val id = reader.nextName()
            if (id !in ids || reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
            val values = mutableMapOf<String, String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                if (field in setOf("full_name", "search_full_name", "first_name", "last_name", "team", "position", "injury_status", "espn_id") && reader.peek() in setOf(JsonToken.STRING, JsonToken.NUMBER)) values[field] = reader.nextString() else reader.skipValue()
            }
            reader.endObject()
            val name = values["full_name"] ?: values["search_full_name"] ?: listOfNotNull(values["first_name"], values["last_name"]).joinToString(" ")
            if (name.isNotBlank()) players[id] = SleeperPlayer(id, name, values["team"], values["position"], values["injury_status"], values["espn_id"])
        }
        reader.endObject()
    }
    players
}

@Composable
fun SleeperFantasyContent(model: MobileViewModel) {
    val prefs by model.library.state.collectAsStateWithLifecycle()
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val api = remember { SleeperFantasyApi(model.container.http) }
    val scope = rememberCoroutineScope()
    var identifier by rememberSaveable { mutableStateOf("") }
    var leagues by remember { mutableStateOf(emptyList<SleeperLeague>()) }
    var week by rememberSaveable { mutableIntStateOf(prefs.sleeperSnapshot?.week ?: 1) }
    var year by rememberSaveable { mutableStateOf(prefs.sleeperLeague?.season ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var disconnect by remember { mutableStateOf(false) }
    LaunchedEffect(prefs.sleeperAccount?.id, year, attempt) {
        val account = prefs.sleeperAccount ?: return@LaunchedEffect
        busy = true; error = null
        try { val state = withContext(Dispatchers.IO) { api.season() }; if (year.isBlank()) { year = state.first; week = state.second }; leagues = withContext(Dispatchers.IO) { api.leagues(account, year.ifBlank { state.first }) } }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = "Sleeper league discovery couldn't refresh. Saved league data is still available." }
        finally { busy = false }
    }
    LaunchedEffect(prefs.sleeperAccount?.id, prefs.sleeperLeague?.id, week, attempt) {
        val account = prefs.sleeperAccount ?: return@LaunchedEffect; val league = prefs.sleeperLeague ?: return@LaunchedEffect
        busy = true
        try {
            val snapshot = withContext(Dispatchers.IO) { api.load(account, league, week) }
            model.library.update { it.copy(sleeperSnapshot = snapshot) }
            val ids = snapshot.teams.flatMap { it.players }.toSet()
            try { val players = sleeperPlayers(model, ids); model.library.update { it.copy(sleeperPlayers = players) } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = "Player names couldn't refresh. Published matchup and roster IDs remain available." }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = "Sleeper couldn't refresh this week. Showing the last received league data." }
        finally { busy = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Sleeper Fantasy", style = MaterialTheme.typography.headlineSmall)
        Text("Connect a public Sleeper NFL account. Matchups, rosters and standings are read-only; no password or access token is needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (prefs.sleeperAccount == null) {
            PhoneField("Sleeper username or user ID", identifier, { identifier = it })
            Button(enabled = !busy && identifier.isNotBlank(), onClick = { busy = true; scope.launch {
                try { val account = withContext(Dispatchers.IO) { api.account(identifier) }; model.library.update { it.copy(sleeperAccount = account) }; identifier = ""; error = null }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = "Account not found. Check your public Sleeper username or user ID." }
                finally { busy = false }
            } }) { Text("Connect Sleeper") }
        } else {
            Text("Connected as ${prefs.sleeperAccount!!.display}", fontWeight = FontWeight.SemiBold)
            PhoneField("Season", year, { value -> if (value.length <= 4 && value.all(Char::isDigit)) year = value }, androidx.compose.ui.text.input.KeyboardType.Number)
            if (leagues.isNotEmpty()) ChoiceRow(leagues.map { it.name }, prefs.sleeperLeague?.name.orEmpty()) { name -> leagues.firstOrNull { it.name == name }?.let { selected -> model.library.update { it.copy(sleeperLeague = selected, sleeperSnapshot = null, sleeperPlayers = emptyMap()) } } }
            if (leagues.isEmpty() && !busy) Text("No NFL leagues found for this season.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            prefs.sleeperLeague?.let { selected ->
                val snapshot = prefs.sleeperSnapshot?.takeIf { it.league.id == selected.id }
                Row { TextButton(onClick = { if (week > 1) week-- }) { Text("Previous") }; Text("Week $week", Modifier.weight(1f).padding(top = 12.dp)); TextButton(onClick = { if (week < 30) week++ }) { Text("Next") } }
                snapshot?.let { current ->
                    Text("${current.league.name} · ${current.league.season} · ${current.league.status}", style = MaterialTheme.typography.titleMedium)
                    Text("Checked ${clockTime(current.updated)}${if (error != null || current.week != week) " · saved week ${current.week}" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    current.home?.let { home -> PhoneCard { Text(current.teams.firstOrNull { it.id == home.roster }?.name ?: "Your team", fontWeight = FontWeight.Bold); if (!settings.spoilerFreeMode) Text(home.points?.toString() ?: "Score unavailable", style = MaterialTheme.typography.headlineMedium); val away = current.opponent; Text(away?.let { current.teams.firstOrNull { t -> t.id == it.roster }?.name } ?: "Bye week / no opponent published"); if (!settings.spoilerFreeMode && away != null) Text(away.points?.toString() ?: "Score unavailable") } }
                    current.teams.firstOrNull { it.id == current.me }?.let { team ->
                        SectionTitle("Your roster")
                        team.players.forEach { id -> val player = prefs.sleeperPlayers[id]; val starter = team.starters.indexOf(id); val slot = if (starter >= 0) current.league.slots.filterNot { it in setOf("BN", "IR") }.getOrNull(starter) ?: "Starter" else if (id in team.reserve) "IR" else "BN"
                            ListItem(headlineContent = { Text(player?.name ?: "Player $id") }, supportingContent = { Text(listOfNotNull(slot, player?.team, player?.position, player?.injury).joinToString(" · ")) }, trailingContent = { if (!settings.spoilerFreeMode) Text(current.home?.playerPoints?.get(id)?.toString() ?: "–") }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
                        }
                    } ?: Text("This account has no roster in the selected league.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatTable("Standings", listOf("Team", "Record", "Points"), current.teams.map { listOf(it.name, "${it.wins ?: "–"}-${it.losses ?: "–"}${it.ties?.takeIf { n -> n > 0 }?.let { n -> "-$n" }.orEmpty()}", if (settings.spoilerFreeMode) "Hidden" else it.pointsFor?.toString() ?: "–") })
                }
            }
            Row { TextButton(onClick = { attempt++ }, enabled = !busy) { Text("Refresh") }; TextButton(onClick = { disconnect = true }) { Text("Disconnect") } }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect Sleeper?") }, text = { Text("Remove this account and its saved league snapshots from this device. No changes are made to Sleeper.") }, confirmButton = { TextButton(onClick = { model.library.update { it.copy(sleeperAccount = null, sleeperLeague = null, sleeperSnapshot = null, sleeperPlayers = emptyMap()) }; leagues = emptyList(); disconnect = false }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
}
