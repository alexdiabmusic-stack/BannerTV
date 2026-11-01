package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.data.fantasy.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun FantasyScreen(model: MobileViewModel) { ScreenList { item { FantasyContent(model) } } }

@Composable
fun FantasyContent(model: MobileViewModel) {
    var provider by rememberSaveable { mutableStateOf("Banner") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ChoiceRow(listOf("Banner", "ESPN", "Sleeper"), provider) { provider = it }
        when (provider) { "Banner" -> NativeFantasyContent(model); "Sleeper" -> SleeperFantasyContent(model); else -> EspnFantasyContent(model) }
    }
}

@Composable
private fun EspnFantasyContent(model: MobileViewModel) {
    val state by model.container.fantasy.state.collectAsStateWithLifecycle()
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sportCode by rememberSaveable { mutableStateOf(FantasySport.Football.code) }
    val sport = FantasySport.fromCode(sportCode) ?: FantasySport.Football
    var season by rememberSaveable(sportCode) { mutableStateOf(sport.currentSeason().toString()) }
    var leagueId by rememberSaveable { mutableStateOf("") }
    var teamId by rememberSaveable { mutableStateOf("") }
    var s2 by remember { mutableStateOf("") }
    var swid by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    LaunchedEffect(state.connection?.key) { if (state.isConnected) model.container.fantasy.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.isConnected) {
            Text(state.snapshot?.leagueName ?: "Your ESPN league", style = MaterialTheme.typography.headlineMedium)
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.isStale) Text("Showing the saved league. ESPN couldn't be refreshed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.snapshot?.let { snapshot ->
                snapshot.matchup?.let { matchup -> PhoneCard {
                    Text("WEEK ${matchup.week}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${matchup.me.name} vs ${matchup.opponent?.name ?: "Bye week"}", style = MaterialTheme.typography.titleMedium)
                    if (!settings.spoilerFreeMode) Text("${matchup.me.points} – ${matchup.opponent?.points ?: "–"}", style = MaterialTheme.typography.headlineMedium)
                } }
                if (snapshot.roster.isNotEmpty()) {
                    SectionTitle("Your roster")
                    snapshot.roster.forEach { player -> ListItem(headlineContent = { Text(player.name) }, supportingContent = { Text(listOfNotNull(player.slot, player.injuryStatus).joinToString(" · ")) }, trailingContent = { if (!settings.spoilerFreeMode) Text(player.points?.toString() ?: "–") }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) }
                }
                if (snapshot.standings.isNotEmpty()) StatTable("Standings", listOf("Team", "Record"), snapshot.standings.map { listOf(it.name, it.record) })
            }
            Row { TextButton(onClick = model.container.fantasy::refresh) { Text("Refresh") }; TextButton(onClick = { disconnect = true }) { Text("Disconnect") } }
        } else {
            Text("Your fantasy, in the game.", style = MaterialTheme.typography.headlineMedium)
            Text("Connect an ESPN league to see your matchup, roster and standings. Private leagues also need your ESPN session cookies.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceRow(FantasySport.entries.map { it.label }, sport.label) { label -> sportCode = FantasySport.entries.first { it.label == label }.code }
            PhoneField("Season", season, { season = it }, KeyboardType.Number)
            PhoneField("League ID", leagueId, { leagueId = it }, KeyboardType.Number)
            PhoneField("Your team ID (optional)", teamId, { teamId = it }, KeyboardType.Number)
            OutlinedTextField(s2, { s2 = it }, Modifier.fillMaxWidth(), label = { Text("espn_s2 (private leagues)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(swid, { swid = it }, Modifier.fillMaxWidth(), label = { Text("SWID (private leagues)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        model.container.fantasy.connect(FantasyConnection(sportCode, season.toInt(), leagueId.trim(), teamId.toIntOrNull()), if (s2.isNotBlank() && swid.isNotBlank()) EspnCookies(s2.trim(), swid.trim()) else null)
                        s2 = ""; swid = ""
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (known: FantasyException) { error = known.message }
                    catch (_: Exception) { error = "Couldn't reach ESPN. Check the league details and try again." }
                    finally { busy = false }
                }
            }, enabled = !busy && season.toIntOrNull() != null && leagueId.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Connecting…" else "Connect ESPN Fantasy") }
        }
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect ESPN Fantasy?") }, text = { Text("The saved session and league cache will be removed from this device.") }, confirmButton = { TextButton(onClick = { model.container.fantasy.disconnect(); disconnect = false }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
}
