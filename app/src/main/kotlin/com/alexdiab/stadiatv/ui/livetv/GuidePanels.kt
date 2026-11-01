package com.alexdiab.stadiatv.ui.livetv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.SettingsHeading
import com.alexdiab.stadiatv.ui.components.SettingsRow
import com.alexdiab.stadiatv.ui.components.SettingsToggle
import com.alexdiab.stadiatv.ui.components.SideRow
import com.alexdiab.stadiatv.ui.components.ValueTrailing
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Type
import kotlinx.coroutines.android.awaitFrame

/** The right-hand panel: which channels and which areas the guide shows, and the HD preference. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FilterPanel(viewModel: GuideViewModel, ui: GuideUi, onClose: () -> Unit) {
    val container = LocalContainer.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    var pickingArea by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(pickingArea) { awaitFrame(); runCatching { first.requestFocus() } }

    Scrim(onClose) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(720.dp)
                .fillMaxHeight()
                .background(Palette.Surface)
                .border(1.dp, Palette.Hairline)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            if (pickingArea) {
                Text("Local area", style = Type.Title3, color = Palette.TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
                Text("Your local stations (CTV, CBC, ABC, NBC ...) come from this area.", style = Type.Callout, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().focusRestorer(first), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ui.markets, key = { it.country + it.key }) { market ->
                        SideRow(
                            market.label, selected = market.key == ui.homeMarket,
                            onFocused = { viewModel.setHomeMarket(market.key); pickingArea = false },
                            subtitle = if (market.country == "CA") "Canada" else if (market.country == "US") "United States" else market.country,
                            modifier = if (market.key == ui.homeMarket) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
                return@Column
            }
            Text("Guide settings", style = Type.Title3, color = Palette.TextPrimary, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item("area") {
                    SettingsRow(
                        "Local area", onClick = { pickingArea = true }, modifier = Modifier.focusRequester(first),
                        subtitle = "Which city's local stations to show",
                        trailing = { ValueTrailing(if (ui.homeMarket.isEmpty()) "Not set" else GuideLogic.titleCase(ui.homeMarket)) },
                    )
                }
                item("allAreas") { SettingsToggle("Stations from every area", options.allAreas, { viewModel.toggleAllAreas() }, subtitle = "Also list local stations from other cities") }
                item("hd") {
                    SettingsToggle(
                        "Prefer HD", settings.preferHdStreams, { value -> container.preferences.update { it.copy(preferHdStreams = value) } },
                        subtitle = if (settings.preferHdStreams) "HD plays first; standard definition is the fallback" else "Standard definition plays first (slower connections)",
                    )
                }
                item("country") {
                    Column {
                        SettingsHeading("Your country")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val home = com.alexdiab.stadiatv.lineup.LineupStore.homeCountryOf(settings)
                            FilterChip("Canada", home == "CA", { viewModel.setHomeCountry("CA") })
                            FilterChip("United States", home == "US", { viewModel.setHomeCountry("US") })
                        }
                    }
                }
                item("countries") {
                    Column {
                        SettingsHeading("Channels from")
                        Text("More countries load more of the guide.", style = Type.Callout, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val active = com.alexdiab.stadiatv.lineup.LineupStore.countriesOf(settings)
                            ui.countries.take(14).forEach { (code, count) ->
                                FilterChip(countryName(code), code in active, { viewModel.toggleCountry(code) }, count = count)
                            }
                        }
                    }
                }
                item("free") { SettingsToggle("Free streaming channels", settings.showFreeStreaming, { value -> container.preferences.update { it.copy(showFreeStreaming = value) } }, subtitle = "Pluto TV and similar: ${ui.freeStreamingCount} channels, no schedules") }
                item("spanish") { SettingsToggle("Spanish-language channels", settings.showSpanishChannels, { value -> container.preferences.update { it.copy(showSpanishChannels = value) } }, subtitle = "Univision, Telemundo and the rest of the Spanish catalogue") }
                item("kids") { SettingsToggle("Hide kids channels", settings.hideKidsChannels, { value -> container.preferences.update { it.copy(hideKidsChannels = value) } }, subtitle = "They stay available under the Kids filter") }
                item("hidden") { SettingsToggle("Show hidden channels", options.showHidden, { viewModel.toggleShowHidden() }, subtitle = "${ui.hiddenCount} hidden") }
                item("manage") { SettingsToggle("Arrange channels", options.managing, { viewModel.toggleManaging() }, subtitle = "Hold OK on a channel to move, hide or favourite it") }
                if (settings.entryOrder.isNotEmpty()) item("reset") { SettingsRow("Reset channel order", onClick = { viewModel.resetOrder() }) }
            }
        }
    }
}

/** The actions for one channel (hold OK on it). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ActionsDialog(row: GuideRow, viewModel: GuideViewModel, managing: Boolean, onPlay: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    Scrim(onClose) {
        Column(
            Modifier
                .align(Alignment.Center)
                .width(660.dp)
                .background(Palette.Surface, RoundedCornerShape(24.dp))
                .border(1.dp, Palette.Hairline, RoundedCornerShape(24.dp))
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(row.entry.name, style = Type.Title3, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.now?.title ?: "Nothing listed", style = Type.Callout, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SettingsRow("Play", onClick = onPlay, icon = Icons.Rounded.PlayArrow, modifier = Modifier.focusRequester(first))
            SettingsRow(
                if (row.isFavorite) "Remove from favorites" else "Add to favorites",
                onClick = { viewModel.toggleFavorite(row.key); onClose() },
                icon = if (row.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            )
            SettingsRow(
                if (row.isHidden) "Show this channel" else "Hide this channel",
                onClick = { viewModel.toggleHidden(row.key); onClose() },
                icon = if (row.isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            )
            if (managing) {
                SettingsRow("Move up", onClick = { viewModel.move(row.key, -1) }, icon = Icons.Rounded.ArrowUpward)
                SettingsRow("Move down", onClick = { viewModel.move(row.key, 1) }, icon = Icons.Rounded.ArrowDownward)
            }
        }
    }
}

@Composable
private fun Scrim(onClose: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        content = content,
    )
}

private fun countryName(code: String): String = when (code) {
    "CA" -> "Canada"
    "US" -> "United States"
    "GB", "UK" -> "United Kingdom"
    "FR" -> "France"
    "ES" -> "Spain"
    "DE" -> "Germany"
    "IT" -> "Italy"
    "MX" -> "Mexico"
    "AU" -> "Australia"
    "IN" -> "India"
    "PT" -> "Portugal"
    "BR" -> "Brazil"
    "NL" -> "Netherlands"
    else -> code
}
