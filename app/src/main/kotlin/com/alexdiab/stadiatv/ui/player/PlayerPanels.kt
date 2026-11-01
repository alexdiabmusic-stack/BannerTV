package com.alexdiab.stadiatv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.live.RankedSource
import com.alexdiab.stadiatv.playback.AspectMode
import com.alexdiab.stadiatv.playback.BufferProfile
import com.alexdiab.stadiatv.playback.PlaybackUi
import com.alexdiab.stadiatv.playback.QualityCap
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.components.ChannelLogo
import com.alexdiab.stadiatv.ui.components.Chip
import com.alexdiab.stadiatv.ui.components.ChipStyle
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.TeamLogo
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates

private val PanelWidth = 700.dp
private val PanelFill = Color(0xF2101319)

@Composable
internal fun PanelFrame(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(PanelWidth)
            .background(PanelFill)
            .drawBehind { drawLine(Palette.HairlineStrong, Offset(0f, 0f), Offset(0f, size.height), 2f) }
            .padding(start = 36.dp, top = 40.dp, end = 32.dp),
    ) {
        Text(title, style = Type.Title2, color = Color.White, maxLines = 1)
        if (subtitle != null) Text(subtitle, style = Type.Callout, color = Color.White.copy(alpha = 0.6f), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.padding(top = 16.dp).weight(1f).fillMaxWidth()) { content() }
    }
}

// region Channel rows

@Composable
private fun ChannelRow(
    channel: Channel,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    detail: String? = null,
    progress: Float? = null,
) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (progress != null) 96.dp else 84.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
        restingBorder = if (isCurrent) Palette.Accent.copy(alpha = 0.7f) else Palette.Hairline,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChannelLogo(channel.logoUrl, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(channel.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail ?: channel.group, style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (progress != null) {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(5.dp).background(Palette.SurfaceHigh, RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Palette.Accent, RoundedCornerShape(3.dp)))
                    }
                }
            }
            if (isFavorite) Icon(Icons.Rounded.Favorite, null, tint = Palette.Live, modifier = Modifier.size(22.dp))
            if (isCurrent) Chip("NOW PLAYING", style = ChipStyle.Accent)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChannelList(
    channels: List<Channel>,
    current: Channel?,
    onSelect: (Channel) -> Unit,
    detailFor: (Channel) -> Pair<String?, Float?> = { null to null },
    favorites: Set<String> = emptySet(),
    header: (LazyListScope.() -> Unit)? = null,
) {
    val currentIndex = channels.indexOfFirst { it.key == current?.key }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header?.invoke(this)
        items(channels, key = { it.key }) { channel ->
            val (detail, progress) = detailFor(channel)
            val isCurrent = channel.key == current?.key
            ChannelRow(
                channel = channel,
                isCurrent = isCurrent,
                isFavorite = channel.key in favorites,
                detail = detail,
                progress = progress,
                onClick = { onSelect(channel) },
                // With nothing playing to land on, focus starts at the top of the list.
                modifier = if (isCurrent || (current == null && channel.key == channels.first().key)) Modifier.initialFocus() else Modifier,
            )
        }
    }
}

// endregion

@Composable
internal fun ChannelsPanel(current: Channel?, channels: List<Channel>, onSelect: (Channel) -> Unit, onClose: () -> Unit, title: String = "Channels") {
    val container = LocalContainer.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf("all") }
    val shown = remember(mode, channels, settings.favoriteChannelIds, current?.group) {
        when (mode) {
            "favourites" -> channels.filter { it.key in settings.favoriteChannelIds || it.id in settings.favoriteChannelIds }
            "group" -> channels.filter { it.group == current?.group }
            else -> channels
        }
    }
    PanelFrame(title, subtitle = "${shown.size} channels") {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                FilterChip("All", mode == "all", { mode = "all" })
                FilterChip("Favourites", mode == "favourites", { mode = "favourites" })
                if (current != null) FilterChip(current.group.take(18), mode == "group", { mode = "group" })
            }
            ChannelList(shown, current, onSelect = { onSelect(it); onClose() }, favorites = settings.favoriteChannelIds)
        }
    }
}

@Composable
internal fun GuidePanel(current: Channel, channels: List<Channel>, onSelect: (Channel) -> Unit, onClose: () -> Unit) {
    val container = LocalContainer.current
    val guide = container.guide
    val now = remember { System.currentTimeMillis() }
    val sameGroup = remember(channels, current.group) { channels.filter { it.group == current.group }.take(300).ifEmpty { channels.take(300) } }
    PanelFrame("Guide", subtitle = current.group) {
        ChannelList(
            sameGroup, current,
            onSelect = { onSelect(it); onClose() },
            detailFor = { channel ->
                val (programme, next) = guide.nowAndNext(channel, now)
                val text = when {
                    programme != null -> "${programme.title}  ·  ${Dates.time(programme.startMillis)}–${Dates.time(programme.endMillis)}" + (next?.let { "   Next: ${it.title}" } ?: "")
                    else -> "No guide data"
                }
                text to programme?.progress(now)
            },
        )
    }
}

@Composable
internal fun RecentsPanel(current: Channel, lineup: List<Channel>, onSelect: (Channel) -> Unit, onClose: () -> Unit) {
    val container = LocalContainer.current
    val history by container.watchHistory.history.collectAsStateWithLifecycle()
    val recents = remember(history, lineup) { container.watchHistory.resolved(lineup).map { it.channel } }
    PanelFrame("Recent", subtitle = "Channels you've watched") {
        if (recents.isEmpty()) Text("Nothing watched yet.", style = Type.Body, color = Color.White.copy(alpha = 0.6f))
        else ChannelList(recents, current, onSelect = { onSelect(it); onClose() })
    }
}

// region Options

@Composable
internal fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null, trailing: (@Composable () -> Unit)? = null) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (detail != null) 76.dp else 64.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
        restingBorder = if (selected) Palette.Accent.copy(alpha = 0.6f) else Palette.Hairline,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = Type.Caption, color = Palette.TextSecondary, maxLines = 1)
            }
            trailing?.invoke()
            if (selected) Icon(Icons.Rounded.Check, null, tint = Palette.AccessibleAccent, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
internal fun OptionHeading(text: String) =
    Text(text.uppercase(), style = Type.Overline, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 6.dp))

/** "HD", or "HD · US feed" when the channel has more than one feed. */
private fun streamLabel(entry: com.alexdiab.stadiatv.lineup.LineupEntry, stream: com.alexdiab.stadiatv.lineup.ResolvedStream): String {
    val feed = stream.identity.feedCountry?.takeIf { entry.feeds.map { f -> f.identity.feedCountry }.distinct().size > 1 }?.let {
        when (it) { "CA" -> "Canadian feed"; "US" -> "US feed"; else -> "$it feed" }
    }
    return listOfNotNull(stream.quality.label, feed, stream.identity.variant.takeIf { it == "west" || it == "pacific" }?.let { "West coast" }).joinToString(" · ")
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OptionsPanel(
    channel: Channel,
    playback: PlaybackUi,
    aspect: AspectMode,
    profile: String,
    isFavorite: Boolean,
    onAspect: (AspectMode) -> Unit,
    onQuality: (QualityCap) -> Unit,
    onAudio: (String) -> Unit,
    onText: (String?) -> Unit,
    entry: com.alexdiab.stadiatv.lineup.LineupEntry?,
    prefs: com.alexdiab.stadiatv.lineup.LineupPrefs,
    onStream: (com.alexdiab.stadiatv.lineup.ResolvedStream) -> Unit,
    onPreferHd: (Boolean) -> Unit,
    onProfile: (BufferProfile) -> Unit,
    onFavorite: () -> Unit,
    onLock: () -> Unit,
    onMultiview: () -> Unit,
    onClose: () -> Unit,
) {
    PanelFrame("Options", subtitle = channel.name) {
        LazyColumn(
            Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(bottom = 80.dp, start = 4.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("fav") {
                OptionRow(
                    if (isFavorite) "Remove from favourites" else "Add to favourites", selected = false, onClick = onFavorite,
                    modifier = Modifier.initialFocus(),
                    trailing = { Icon(if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = Palette.Live, modifier = Modifier.size(26.dp)) },
                )
            }
            item("lock") { OptionRow("Lock controls", false, onLock, detail = "Prevents accidental button presses", trailing = { Icon(Icons.Rounded.Lock, null, tint = Palette.TextSecondary, modifier = Modifier.size(24.dp)) }) }
            item("multiview") { OptionRow("Watch with other streams", false, onMultiview, detail = "Multiview: side by side, picture in picture, 3-up or 4-up") }
            if (entry != null) {
                val streams = entry.streamsInOrder(prefs)
                if (streams.size > 1) {
                    item("h-stream") { OptionHeading("Stream") }
                    items(streams, key = { "stream-${it.channel.key}" }) { stream ->
                        OptionRow(
                            streamLabel(entry, stream), stream.channel.key == channel.key, { onStream(stream) },
                            detail = if (stream.name.isBackup) "Backup copy" else null,
                        )
                    }
                }
                item("prefer-hd") {
                    OptionRow(
                        if (prefs.preferHd) "Prefer HD: on" else "Prefer HD: off", false, { onPreferHd(!prefs.preferHd) },
                        detail = if (prefs.preferHd) "HD plays first. Turn off for standard definition." else "Standard definition plays first.",
                    )
                }
            }
            item("h-aspect") { OptionHeading("Picture") }
            items(AspectMode.entries.toList(), key = { "aspect-${it.name}" }) { OptionRow(it.label, aspect == it, { onAspect(it) }) }
            item("h-quality") { OptionHeading("Quality") }
            items(QualityCap.entries.toList(), key = { "quality-${it.name}" }) { OptionRow(it.label, playback.qualityCap == it, { onQuality(it) }) }
            if (playback.audioTracks.size > 1) {
                item("h-audio") { OptionHeading("Audio") }
                items(playback.audioTracks, key = { "audio-${it.id}" }) { OptionRow(it.label, it.selected, { onAudio(it.id) }) }
            }
            if (playback.textTracks.isNotEmpty()) {
                item("h-text") { OptionHeading("Subtitles") }
                item("text-off") { OptionRow("Off", playback.textTracks.none { it.selected }, { onText(null) }) }
                items(playback.textTracks, key = { "text-${it.id}" }) { OptionRow(it.label, it.selected, { onText(it.id) }) }
            }
            item("h-buffer") { OptionHeading("Buffering") }
            items(BufferProfile.entries.toList(), key = { "buffer-${it.name}" }) { OptionRow(it.label, profile == it.name, { onProfile(it) }, detail = it.detail) }
            item("h-engine") { OptionHeading("Stream shield") }
            item("engine-state") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.SurfaceElevated, RoundedCornerShape(14.dp))
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (playback.engineActive) "Protected transport stream" else "Standard HLS playback",
                        style = Type.BodyStrong,
                        color = Palette.TextPrimary,
                    )
                    val detail = if (playback.engineActive) {
                        val rate = if (playback.playbackRate == 1f) "normal speed" else "${"%.3f".format(playback.playbackRate)}× recovery speed"
                        "${playback.reserveSeconds}s buffered · $rate"
                    } else {
                        "HLS handles its own segment recovery. Raw Xtream streams use the protected reconnect-and-splice engine."
                    }
                    Text(detail, style = Type.Caption, color = Palette.TextSecondary)
                }
            }
        }
    }
}

// endregion

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SportsPanel(match: Match, sources: List<RankedSource>, currentKey: String, onSelect: (Channel) -> Unit, onClose: () -> Unit) {
    PanelFrame("Match", subtitle = match.league.name) {
        LazyColumn(
            Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(bottom = 80.dp, start = 4.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("score") {
                Row(
                    Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(18.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TeamLogo(match.away.logoUrl, 72.dp)
                        Text(match.away.abbreviation, style = Type.BodyStrong, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${match.away.score ?: "–"} – ${match.home.score ?: "–"}", style = Type.ScoreLg, color = Color.White)
                        Text(match.statusDetail, style = Type.Callout, color = Palette.Live)
                        match.live.situation.firstOrNull()?.let { Text(it, style = Type.Caption, color = Color.White.copy(alpha = 0.7f)) }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TeamLogo(match.home.logoUrl, 72.dp)
                        Text(match.home.abbreviation, style = Type.BodyStrong, color = Color.White)
                    }
                }
            }
            if (sources.isNotEmpty()) item("h") { OptionHeading("Other ways to watch") }
            items(sources.take(20), key = { it.channel.key }) { source ->
                ChannelRow(
                    channel = source.channel,
                    isCurrent = source.channel.key == currentKey,
                    detail = source.label,
                    onClick = { onSelect(source.channel); onClose() },
                    modifier = if (source.channel.key == currentKey) Modifier.initialFocus() else Modifier,
                )
            }
        }
    }
}
