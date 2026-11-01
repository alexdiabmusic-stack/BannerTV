@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.live.Playlist
import com.alexdiab.stadiatv.live.PlaylistKind
import com.alexdiab.stadiatv.playback.BufferProfile
import com.alexdiab.stadiatv.playback.PlaybackLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI

@Composable
fun PhoneOnboarding(model: MobileViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val playlists by model.container.playlists.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(step > 0 && !adding) { step-- }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = { if (step < 3) step++ else { model.container.preferences.completeOnboarding(); model.refresh() } },
                        enabled = step != 1 || settings.selectedLeagueIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(if (step == 3) "Start watching" else if (step == 0) "Get started" else "Continue") }
                    if (step > 0) TextButton(onClick = { step-- }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
                }
            }
        },
    ) { inset ->
        Box(Modifier.padding(inset).statusBarsPadding()) {
            ScreenList {
                item { Spacer(Modifier.height(24.dp)); StatusPill("BANNER TV"); Spacer(Modifier.height(16.dp)) }
                item {
                    Text(when (step) { 0 -> "A better seat.\nEvery game."; 1 -> "Your kind of sport."; 2 -> "Who do you follow?"; else -> "Bring your channels." }, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(when (step) { 0 -> "Your teams, live television, and everything around the game. All in one place."; 1 -> "Pick the leagues you want to see. You can change these any time."; 2 -> "Star a team to put their games first. You can also do this later."; else -> "Connect the playlist from your provider, or explore sports first and add it later." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (step) {
                    0 -> {
                        item { SetupBenefit(Icons.Rounded.Sports, "Made for your sports", "Live scores, schedules and match centres.") }
                        item { SetupBenefit(Icons.Rounded.LiveTv, "Your personal television", "Your own channels and their real programme guide.") }
                        item { SetupBenefit(Icons.Rounded.PlayCircle, "Watch your way", "Mini-player, picture-in-picture and multiview.") }
                    }
                    1 -> {
                        for (group in League.groups) {
                            item("group-${group.name}") { SectionTitle(group.label) }
                            items(League.inGroup(group), key = { it.path }) { league ->
                                ListItem(
                                    modifier = Modifier.toggleable(league.path in settings.selectedLeagueIds, role = Role.Checkbox) { model.container.preferences.toggleLeague(league) },
                                    headlineContent = { Text(league.name) },
                                    leadingContent = { Checkbox(league.path in settings.selectedLeagueIds, onCheckedChange = null) },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                                )
                            }
                        }
                    }
                    2 -> item { TeamsPicker(model) }
                    3 -> {
                        items(playlists.playlists, key = { it.id }) { p -> PhoneCard { Text(p.name, style = MaterialTheme.typography.titleMedium); Text("Connected", color = BannerGreen) } }
                        item { Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Text("Add playlist") } }
                        if (playlists.isEmpty) item { Text("No playlist? You can still follow teams, read stories, and explore scores.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.93f)) { PlaylistEditor(null, model, { adding = false }) }
    }
}

@Composable
private fun SetupBenefit(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    PhoneCard {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun TeamsScreen(model: MobileViewModel) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    ScreenList {
        item { Text("Make Banner yours.", style = MaterialTheme.typography.headlineLarge) }
        item { SectionTitle("Leagues") }
        items(League.ALL, key = { it.path }) { league ->
            ListItem(modifier = Modifier.toggleable(league.path in settings.selectedLeagueIds, role = Role.Switch) { model.container.preferences.toggleLeague(league) }, headlineContent = { Text(league.name) }, trailingContent = { Switch(league.path in settings.selectedLeagueIds, onCheckedChange = null) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
        }
        item { SectionTitle("Teams") }
        item { TeamsPicker(model) }
    }
}

@Composable
private fun TeamsPicker(model: MobileViewModel) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    var path by rememberSaveable { mutableStateOf(settings.followedLeagues.firstOrNull()?.path ?: League.ALL.first().path) }
    var query by rememberSaveable { mutableStateOf("") }
    val league = League.find(path) ?: League.ALL.first()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceRow(League.ALL.map { it.shortName }, league.shortName) { short -> path = League.ALL.first { it.shortName == short }.path }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Find a team") }, singleLine = true)
        AsyncSection(path, { model.container.sports.teams(league) }) { teams ->
            if (teams.isEmpty()) Text("Team information isn't available for this league.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            teams.filter { it.name.contains(query, true) }.forEach { team ->
                ListItem(
                    headlineContent = { Text(team.name) }, leadingContent = { Logo(team.logoUrl, team.abbreviation, 32.dp) },
                    trailingContent = { IconToggleButton(checked = settings.isFavorite(team), onCheckedChange = { model.container.preferences.toggleFavorite(team) }) { Icon(if (settings.isFavorite(team)) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Follow ${team.name}") } },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        }
    }
}

@Composable
fun PlaylistsScreen(model: MobileViewModel) {
    val state by model.container.playlists.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<Playlist?>(null) }
    ScreenList {
        item { Button(onClick = { model.open(PhonePage.PlaylistForm()) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Text("Add playlist") } }
        if (state.isEmpty) item { EmptyState("Your television starts here", "Connect an M3U link or Xtream login from your provider.") }
        items(state.playlists, key = { it.id }) { playlist ->
            PhoneCard {
                Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                Text("${state.counts[playlist.id] ?: 0} channels · ${if (playlist.kind == PlaylistKind.M3U) "M3U" else "Stream login"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (playlist.id in state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (playlist.id in state.errors) Text("Couldn't refresh this playlist. Check the connection details.", color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = { model.open(PhonePage.PlaylistForm(playlist)) }) { Text("Edit") }
                    TextButton(onClick = { model.container.playlists.refresh(playlist.id) }) { Text("Refresh") }
                    TextButton(onClick = { model.container.playlists.setDefault(playlist.id) }, enabled = state.defaultId != playlist.id) { Text(if (state.defaultId == playlist.id) "Default" else "Set default") }
                }
                TextButton(onClick = { removing = playlist }) { Text("Remove playlist", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    removing?.let { playlist -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${playlist.name}?") }, text = { Text("Its channels and saved login will be removed from this device.") }, confirmButton = { TextButton(onClick = { model.container.playlists.remove(playlist.id); removing = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}

fun validProviderUrl(value: String): Boolean = runCatching { URI(value.trim()).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() } }.getOrDefault(false)

@Composable
fun PlaylistEditor(editing: Playlist?, model: MobileViewModel, onSaved: () -> Unit) {
    val store = model.container.playlists
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val credentials = remember(editing?.id) { editing?.let { store.credentials(it.id) } }
    var kind by remember { mutableStateOf(editing?.kind ?: PlaylistKind.M3U) }
    var name by rememberSaveable(editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
    // M3U URLs can contain a login/token; never write them to a saved-instance-state bundle.
    var address by remember(editing?.id) { mutableStateOf(if (editing?.kind == PlaylistKind.XTREAM) editing.host else editing?.m3uUrl.orEmpty()) }
    var username by remember { mutableStateOf(credentials?.username.orEmpty()) }
    var password by remember { mutableStateOf(credentials?.password.orEmpty()) }
    var userAgent by remember { mutableStateOf(editing?.userAgent.orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = name.isNotBlank() && validProviderUrl(address) && (kind == PlaylistKind.M3U || username.isNotBlank() && password.isNotBlank())
    fun save() {
        if (!valid || checking) return
        checking = true; error = null; keyboard?.hide()
        scope.launch {
            try {
                val result = if (kind == PlaylistKind.M3U) store.validateM3u(address.trim(), userAgent.trim()) else store.validateXtream(address.trim(), username.trim(), password, userAgent.trim())
                result.onSuccess {
                    if (editing == null) {
                        if (kind == PlaylistKind.M3U) store.addM3u(name.trim(), address.trim(), userAgent.trim()) else store.addXtream(name.trim(), address.trim(), username.trim(), password, userAgent.trim())
                    } else {
                        if (kind == PlaylistKind.M3U) store.replaceM3u(editing.id, name.trim(), address.trim(), userAgent.trim()) else store.replaceXtream(editing.id, name.trim(), address.trim(), username.trim(), password, userAgent.trim())
                    }
                    onSaved()
                }.onFailure { error = "Couldn't connect. Check the server address, login details and network, then try again." }
            } catch (cancel: CancellationException) { throw cancel }
            finally { checking = false }
        }
    }
    ScreenList {
        item { Text("Connect your provider", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(8.dp)); Text("Your provider's playlist supplies both the channels and their programme guide.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (editing == null) item { ChoiceRow(listOf("M3U link", "Stream login"), if (kind == PlaylistKind.M3U) "M3U link" else "Stream login") { kind = if (it == "M3U link") PlaylistKind.M3U else PlaylistKind.XTREAM } }
        item { PhoneField("Playlist name", name, { name = it }, enabled = !checking) }
        item { PhoneField(if (kind == PlaylistKind.M3U) "Playlist URL" else "Server address", address, { address = it }, keyboardType = KeyboardType.Uri, enabled = !checking) }
        if (kind == PlaylistKind.XTREAM) {
            item { PhoneField("Username", username, { username = it }, enabled = !checking) }
            item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, enabled = !checking, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide password" else "Show password") } }) }
        }
        item { PhoneField("User-Agent (optional)", userAgent, { userAgent = it }, enabled = !checking) }
        if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
        item { Button(onClick = ::save, enabled = valid && !checking, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { if (checking) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }; Text(if (checking) "Checking connection…" else if (editing == null) "Check & add playlist" else "Check & save") } }
        item { Text("BannerTV uses the channels and guide supplied by your own playlist. Xtream logins are encrypted; playlist links and channel caches stay in private app storage.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun PhoneField(label: String, value: String, onValue: (String) -> Unit, keyboardType: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next))
}

@Composable
fun SettingsScreen(model: MobileViewModel) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val playlists by model.container.playlists.state.collectAsStateWithLifecycle()
    val guide by model.container.guide.state.collectAsStateWithLifecycle()
    ScreenList {
        item { PhoneCard(onClick = { model.open(PhonePage.Playlists) }) { Text("Your television", style = MaterialTheme.typography.titleLarge); Text("${playlists.playlists.size} playlists · ${playlists.channels.size} channels", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Manage playlists", color = MaterialTheme.colorScheme.primary) } }
        item { SectionTitle("Watching") }
        item { PhoneCard {
            SettingSwitch("Prefer HD streams", settings.preferHdStreams) { model.container.preferences.update { s -> s.copy(preferHdStreams = it) } }
            SettingSwitch("Allow 4K streams", settings.preferUhdStreams) { model.container.preferences.update { s -> s.copy(preferUhdStreams = it) } }
            SettingSwitch("Subtitles", settings.captionsEnabled) { model.container.preferences.update { s -> s.copy(captionsEnabled = it) } }
            Text("Preferred audio language", style = MaterialTheme.typography.titleMedium)
            val languages = (listOf("", "en", "fr", "es", "pt", "de", "it", "ar", "hi", "pa") + settings.preferredAudioLanguage).map(PlaybackLanguages::normalize).distinct()
            ChoiceRow(languages.map(PlaybackLanguages::label), PlaybackLanguages.label(settings.preferredAudioLanguage)) { label -> model.container.preferences.update { it.copy(preferredAudioLanguage = languages.first { lang -> PlaybackLanguages.label(lang) == label }) } }
            Text("Used when the stream provides it. Audio selection in the player also updates this preference.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Buffering profile", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(BufferProfile.entries.map { it.label }, model.profile().label) { label -> model.container.preferences.update { it.copy(playerBufferProfile = BufferProfile.entries.first { p -> p.label == label }.name) } }
        } }
        item { SectionTitle("Sports & personalization") }
        item { PhoneCard(onClick = { model.open(PhonePage.Premium) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) { Text("Premium & purchases", style = MaterialTheme.typography.titleMedium); Text("Access status, Google Play & restore", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Rounded.ChevronRight, null)
            }
        } }
        item { PhoneCard {
            TextButton(onClick = { model.open(PhonePage.Teams) }) { Text("Teams & leagues") }
            TextButton(onClick = { model.open(PhonePage.Stats()) }) { Text("Stats · standings, leaders & rosters") }
            if (library.hiddenGameKeys.isNotEmpty()) TextButton(onClick = { model.library.update { it.copy(hiddenGameKeys = emptySet()) } }) { Text("Restore ${library.hiddenGameKeys.size} hidden games") }
            TextButton(onClick = { model.open(PhonePage.Fantasy) }) { Text("Fantasy · Banner, ESPN & Sleeper") }
            SettingSwitch("Spoiler-free mode", settings.spoilerFreeMode) { model.container.preferences.update { s -> s.copy(spoilerFreeMode = it) } }
            SettingSwitch("Live score overlay", settings.showLiveScoreBadge) { model.container.preferences.update { s -> s.copy(showLiveScoreBadge = it) } }
            Text("Score delay", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(listOf("Off", "30s", "60s", "90s"), if (settings.sportsScoreDelaySeconds == 0) "Off" else "${settings.sportsScoreDelaySeconds}s") { value -> model.container.preferences.update { it.copy(sportsScoreDelaySeconds = value.removeSuffix("s").toIntOrNull() ?: 0) } }
        } }
        item { SectionTitle("Fantasy context") }
        item { PhoneCard {
            SettingSwitch("Fantasy on Home", library.fantasyDisplay.showHome) { enabled -> model.library.update { it.copy(fantasyDisplay = it.fantasyDisplay.copy(showHome = enabled)) } }
            SettingSwitch("Fantasy game-card badges", library.fantasyDisplay.showLive) { enabled -> model.library.update { it.copy(fantasyDisplay = it.fantasyDisplay.copy(showLive = enabled)) } }
            SettingSwitch("Fantasy guide badges", library.fantasyDisplay.showGuide) { enabled -> model.library.update { it.copy(fantasyDisplay = it.fantasyDisplay.copy(showGuide = enabled)) } }
            Text("Rostered players' team fixtures, not confirmed participation. Guide badges use only your playlist's own listings and are hidden in spoiler-free mode.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { SectionTitle("Game notifications") }
        item { NotificationSettings(model) }
        item { SectionTitle("Appearance") }
        item { ChoiceRow(listOf("System", "Dark", "Light"), library.appearance) { value -> model.library.update { it.copy(appearance = value) } } }
        item { SectionTitle("Programme guide") }
        item { PhoneCard {
            Text(if (guide.isLoading) guide.progress ?: "Updating guide…" else "${guide.channelsWithGuide} channels with listings", style = MaterialTheme.typography.titleMedium)
            guide.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Text("Guide listings come from your playlist's own XMLTV data.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Home country", style = MaterialTheme.typography.titleMedium)
            ChoiceRow(listOf("Auto", "Canada", "United States"), when (settings.homeCountry) { "CA" -> "Canada"; "US" -> "United States"; else -> "Auto" }) { value -> model.container.preferences.update { it.copy(homeCountry = when (value) { "Canada" -> "CA"; "United States" -> "US"; else -> "" }) } }
            Button(onClick = { model.container.refreshGuide(force = true) }, enabled = !guide.isLoading) { Text("Refresh guide") }
        } }
        item { SectionTitle("Library") }
        item { PhoneCard {
            TextButton(onClick = { model.open(PhonePage.SavedArticles) }) { Text("Saved articles") }
            TextButton(onClick = { model.open(PhonePage.History) }) { Text("Watch history") }
            TextButton(onClick = { model.open(PhonePage.Podcasts) }) { Text("Podcasts, queue & downloads") }
            TextButton(onClick = { model.open(PhonePage.CloudBackup) }) { Text("Cloud backup & restore") }
            TextButton(onClick = { model.open(PhonePage.Cinema) }) { Text("Movies & shows") }
            TextButton(onClick = { model.open(PhonePage.Addons) }) { Text("Manage streaming add-ons") }
            TextButton(onClick = { model.open(PhonePage.ChannelGroups) }) { Text("Custom channel groups") }
            val context = androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick = { if (!PhoneWidgets.requestPin(context)) model.message = "Add Banner sports scores from your launcher's Widgets menu." }) { Text("Add home-screen sports widget") }
            TextButton(onClick = { model.container.preferences.resetOnboarding() }) { Text("Redo setup") }
        } }
        item { SectionTitle("News preferences") }
        item { PhoneCard {
            Text("${library.hiddenStoryIds.size} hidden stories · ${library.mutedNewsSources.size} muted publishers", style = MaterialTheme.typography.bodyMedium)
            library.mutedNewsSources.sorted().forEach { source -> TextButton(onClick = { model.library.update { it.copy(mutedNewsSources = it.mutedNewsSources - source) } }) { Text("Unmute $source") } }
            TextButton(onClick = { model.library.update { it.copy(hiddenStoryIds = emptySet(), mutedNewsSources = emptySet()) } }) { Text("Reset news recommendations") }
        } }
        item { Text("BannerTV for Android · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = value, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        // One labelled target: the decorative switch must not create a second focus/click action.
        Switch(checked = value, onCheckedChange = null)
    }
}
