package com.alexdiab.stadiatv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Scoreboard
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.BuildConfig
import com.alexdiab.stadiatv.data.Favourites
import com.alexdiab.stadiatv.live.PlaylistKind
import com.alexdiab.stadiatv.playback.BufferProfile
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.ChevronTrailing
import com.alexdiab.stadiatv.ui.components.PagePivot
import com.alexdiab.stadiatv.ui.components.SettingsHeading
import com.alexdiab.stadiatv.ui.components.SettingsRow
import com.alexdiab.stadiatv.ui.components.SettingsToggle
import com.alexdiab.stadiatv.ui.components.Spinner
import com.alexdiab.stadiatv.ui.components.ValueTrailing
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.TopBarInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

private val Languages = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
    "nl" to "Dutch", "ar" to "Arabic", "tr" to "Turkish", "pl" to "Polish", "ru" to "Russian", "el" to "Greek",
)
private val DelayOptions = listOf(0, 10, 20, 30, 45, 60)
private val TimeoutOptions = listOf(3, 5, 8, 12)

private fun <T> List<T>.after(current: T): T = this[(indexOf(current).let { if (it < 0) -1 else it } + 1) % size]

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val lineup by container.playlists.state.collectAsStateWithLifecycle()
    val guide by container.guide.state.collectAsStateWithLifecycle()
    val fantasy by container.fantasy.state.collectAsStateWithLifecycle()
    var cleared by remember { mutableStateOf(false) }

    PagePivot(0.3f) {
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = TopBarInset, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            item("title") { Text("Settings", style = Type.Title1, color = Palette.TextPrimary, modifier = Modifier.padding(bottom = 4.dp)) }

            item("h-playlists") { SettingsHeading("Playlists", Icons.Rounded.Link) }
            lineup.playlists.forEach { playlist ->
                item("playlist-${playlist.id}") {
                    val loading = playlist.id in lineup.loading
                    val error = lineup.errors[playlist.id]
                    SettingsRow(
                        playlist.name,
                        onClick = { navigator.push(Route.EditPlaylist(playlist.id)) },
                        subtitle = error ?: playlist.summary,
                        icon = if (playlist.kind == PlaylistKind.M3U) Icons.Rounded.Link else Icons.Rounded.VpnKey,
                        trailing = {
                            if (loading) Spinner(size = 30.dp)
                            else Text(
                                if (error != null) "Error" else "${lineup.counts[playlist.id] ?: 0} channels" + if (playlist.id == lineup.defaultId && lineup.playlists.size > 1) " · Default" else "",
                                style = Type.Callout, color = if (error != null) Palette.Live else Palette.TextSecondary,
                            )
                        },
                    )
                }
            }
            item("add-playlist") { SettingsRow(if (lineup.isEmpty) "Add a playlist" else "Add another playlist", { navigator.push(Route.AddPlaylist) }, subtitle = "M3U link or stream login", icon = Icons.Rounded.AddLink) }

            item("h-teams") { SettingsHeading("Favourites", Icons.Rounded.Star) }
            item("teams") {
                SettingsRow(
                    "Sports, leagues & teams", { navigator.push(Route.TeamPicker) },
                    subtitle = Favourites.summary(settings),
                    icon = Icons.Rounded.Star, trailing = { ChevronTrailing() },
                )
            }

            item("h-fantasy") { SettingsHeading("Fantasy", Icons.Rounded.EmojiEvents) }
            val connection = fantasy.connection
            if (connection == null) {
                item("fantasy-connect") {
                    SettingsRow("Connect ESPN Fantasy", { navigator.push(Route.FantasyConnect) }, subtitle = "NFL · NHL · NBA · MLB", icon = Icons.Rounded.EmojiEvents, trailing = { ChevronTrailing() })
                }
            } else {
                item("fantasy") {
                    val team = fantasy.snapshot?.myTeam
                    SettingsRow(
                        fantasy.snapshot?.leagueName ?: connection.leagueName ?: "ESPN Fantasy", { navigator.push(Route.Fantasy) },
                        subtitle = listOfNotNull(connection.sport?.label, team?.let { "${it.name} · ${it.record}" }).joinToString(" · "),
                        icon = Icons.Rounded.EmojiEvents, trailing = { ChevronTrailing() },
                    )
                }
                item("fantasy-disconnect") {
                    SettingsRow("Disconnect Fantasy", { container.fantasy.disconnect() }, subtitle = "Remove this league and its saved cookies", icon = Icons.Rounded.LinkOff, destructive = true)
                }
            }

            item("h-scores") { SettingsHeading("Live Scores", Icons.Rounded.Scoreboard) }
            item("overlay") { SettingsToggle("Live score overlay", settings.showLiveScoreBadge, { v -> container.preferences.update { it.copy(showLiveScoreBadge = v) } }, subtitle = "Show the score while you watch a game", icon = Icons.Rounded.Scoreboard) }
            item("spoiler") { SettingsToggle("Spoiler-free mode", settings.spoilerFreeMode, { v -> container.preferences.update { it.copy(spoilerFreeMode = v) } }, subtitle = "Hide scores and results everywhere", icon = Icons.Rounded.VisibilityOff) }
            item("delay") {
                SettingsRow(
                    "Score delay", { container.preferences.update { it.copy(sportsScoreDelaySeconds = DelayOptions.after(it.sportsScoreDelaySeconds)) } },
                    subtitle = "Match the overlay to a slightly delayed stream", icon = Icons.Rounded.Timer,
                    trailing = { ValueTrailing(if (settings.sportsScoreDelaySeconds == 0) "Off" else "${settings.sportsScoreDelaySeconds}s") },
                )
            }

            item("h-playback") { SettingsHeading("Playback", Icons.Rounded.PlayCircle) }
            item("language-row") {
                SettingsRow("Streaming languages", {}, subtitle = "Used to rank streams for each match", icon = Icons.Rounded.Language)
            }
            item("languages") {
                FlowRow(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Languages.forEach { (code, label) ->
                        FilterChip(label, code in settings.preferredStreamLanguages, {
                            container.preferences.update {
                                val next = if (code in it.preferredStreamLanguages) it.preferredStreamLanguages - code else it.preferredStreamLanguages + code
                                it.copy(preferredStreamLanguages = next.ifEmpty { setOf("en") })
                            }
                        })
                    }
                }
            }
            item("prefer-hd") {
                SettingsToggle(
                    "Prefer HD", settings.preferHdStreams, { v -> container.preferences.update { it.copy(preferHdStreams = v) } },
                    subtitle = if (settings.preferHdStreams) "HD plays first; standard definition is the fallback" else "Standard definition plays first (slower connections)",
                    icon = Icons.Rounded.Speed,
                )
            }
            item("prefer-uhd") {
                SettingsToggle(
                    "Prefer 4K when available", settings.preferUhdStreams, { v -> container.preferences.update { it.copy(preferUhdStreams = v) } },
                    subtitle = "Off keeps 1080p first, which Fire TV sticks play smoothly", icon = Icons.Rounded.PlayCircle,
                )
            }
            item("captions") {
                val options = listOf("" to "Off", "en" to "English", "fr" to "French", "es" to "Spanish")
                val current = if (!settings.captionsEnabled) options[0] else options.firstOrNull { it.first == settings.captionLanguage.take(2) } ?: options[1]
                SettingsRow(
                    "Subtitles", {
                        val next = options[(options.indexOf(current) + 1) % options.size]
                        container.preferences.update { it.copy(captionsEnabled = next.first.isNotEmpty(), captionLanguage = next.first) }
                    },
                    subtitle = "Turned on automatically when a channel offers them in this language", icon = Icons.Rounded.Language,
                    trailing = { ValueTrailing(current.second) },
                )
            }
            item("buffer") {
                val current = BufferProfile.entries.firstOrNull { it.name == settings.playerBufferProfile } ?: BufferProfile.Balanced
                SettingsRow(
                    "Buffering", { container.preferences.update { it.copy(playerBufferProfile = BufferProfile.entries.after(current).name) } },
                    subtitle = current.detail, icon = Icons.Rounded.Speed, trailing = { ValueTrailing(current.label) },
                )
            }
            item("timeout") {
                SettingsRow(
                    "Controls timeout", { container.preferences.update { it.copy(playerPanelTimeoutSeconds = TimeoutOptions.after(it.playerPanelTimeoutSeconds)) } },
                    subtitle = "How long player controls stay on screen", icon = Icons.Rounded.Timer,
                    trailing = { ValueTrailing("${settings.playerPanelTimeoutSeconds}s") },
                )
            }

            item("h-data") { SettingsHeading("Guide & Data", Icons.Rounded.CalendarMonth) }
            item("guide") {
                SettingsRow(
                    "Refresh TV guide", { container.refreshGuide(force = true) },
                    subtitle = when {
                        guide.isLoading -> "Loading the guide…"
                        guide.error != null -> guide.error
                        guide.programmeCount > 0 -> "${guide.programmeCount} programmes · ${guide.channelsWithGuide} channels"
                        else -> "No guide loaded"
                    },
                    icon = Icons.Rounded.CalendarMonth, trailing = { if (guide.isLoading) Spinner(size = 30.dp) },
                )
            }
            item("home-country") {
                val home = com.alexdiab.stadiatv.lineup.LineupStore.homeCountryOf(settings)
                SettingsRow(
                    "Home country", { container.preferences.update { it.copy(homeCountry = if (home == "CA") "US" else "CA") } },
                    subtitle = "Decides whose feed plays first when both have a schedule. Choose your local area in the TV Guide filters.",
                    icon = Icons.Rounded.Language, trailing = { ValueTrailing(if (home == "CA") "Canada" else "United States") },
                )
            }
            item("refresh") { SettingsRow("Refresh playlists", { container.playlists.refreshAll() }, subtitle = "Reload every channel list", icon = Icons.Rounded.Autorenew) }
            item("cache") {
                SettingsRow(
                    "Clear image & data cache",
                    { container.imageLoader.memoryCache?.clear(); container.imageLoader.diskCache?.clear(); container.http.invalidate(); cleared = true },
                    subtitle = if (cleared) "Cleared" else "Frees storage on this device", icon = Icons.Rounded.AutoDelete,
                )
            }

            item("h-setup") { SettingsHeading("Setup") }
            item("redo") { SettingsRow("Redo setup", { container.preferences.resetOnboarding() }, subtitle = "Re-run setup to choose sports, leagues and teams", icon = Icons.Rounded.Autorenew) }
            item("about") {
                Text(
                    "BannerTV ${BuildConfig.VERSION_NAME}  ·  Built for Fire TV",
                    style = Type.Caption, color = Palette.TextTertiary, modifier = Modifier.padding(top = 20.dp, start = 4.dp),
                )
            }
        }
    }
}
