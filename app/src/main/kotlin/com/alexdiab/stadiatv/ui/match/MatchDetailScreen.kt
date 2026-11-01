package com.alexdiab.stadiatv.ui.match

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.Article
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.TeamSide
import com.alexdiab.stadiatv.live.LinkTier
import com.alexdiab.stadiatv.live.RankedSource
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.common.rememberAsync
import com.alexdiab.stadiatv.data.odds.MatchOdds
import com.alexdiab.stadiatv.data.odds.formatAmericanOdds
import com.alexdiab.stadiatv.ui.components.ArticleCard
import com.alexdiab.stadiatv.ui.components.ChannelLogo
import com.alexdiab.stadiatv.ui.components.Chip
import com.alexdiab.stadiatv.ui.components.ChipStyle
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.LiveBadge
import com.alexdiab.stadiatv.ui.components.LocalSpoilerFree
import com.alexdiab.stadiatv.ui.components.OverlayContentTop
import com.alexdiab.stadiatv.ui.components.OverlayScaffold
import com.alexdiab.stadiatv.ui.components.PagePivot
import com.alexdiab.stadiatv.ui.components.SectionHeader
import com.alexdiab.stadiatv.ui.components.Shelf
import com.alexdiab.stadiatv.ui.components.TeamLogo
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.Tab
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MatchDetailScreen(initial: Match) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val feed by container.matchFeed.sections.collectAsStateWithLifecycle()
    val sourcesByMatch by container.streams.sources.collectAsStateWithLifecycle()
    val lineup by container.playlists.state.collectAsStateWithLifecycle()
    val guide by container.guide.state.collectAsStateWithLifecycle()

    // The feed refreshes scores underneath us; always show the freshest copy of this game.
    val match = remember(feed.all, initial) { feed.all.firstOrNull { it.key == initial.key } ?: initial }
    val sources = sourcesByMatch[match.key]?.let { container.streams.sourcesFor(match).sources }.orEmpty()

    LaunchedEffect(com.alexdiab.stadiatv.live.StreamAvailabilityStore.event(match), match.state, lineup.revision, guide.revision) {
        container.streams.ensure(match, lineup.channels, { container.guide.linkerProgrammes() }, container.guide::guideIdFor, guide.revision * 31 + container.lineup.lineup.value.revision)
    }
    val news = rememberAsync("news-${match.key}") { relevantNews(container.news.forLeague(match.league, 40), match) }

    // Odds are silently absent when no key is configured, the league isn't covered, or the game is over.
    val odds = rememberAsync("odds-${match.key}") { if (match.state == GameState.FINAL) null else container.odds.odds(match) }
    val matchOdds = (odds.state.value as? com.alexdiab.stadiatv.ui.common.Async.Success)?.value

    val confirmed = sources.filter { it.isConfirmed }
    val possible = sources.filterNot { it.isConfirmed }
    val best = confirmed.firstOrNull()
    val lineupNow by container.lineup.lineup.collectAsStateWithLifecycle()
    val lineupName: (RankedSource) -> String = { lineupNow.entryFor(it.channel.key)?.name ?: it.channel.name }
    val newsItems = (news.state.value as? com.alexdiab.stadiatv.ui.common.Async.Success)?.value.orEmpty()
    val play: (RankedSource) -> Unit = { selected ->
        container.streams.sourcesFor(match).sources.firstOrNull { it.channel.key == selected.channel.key }?.let { current ->
            navigator.push(Route.Player(current.channel, match.takeIf { current.isConfirmed }))
        }
    }

    OverlayScaffold(
        title = match.shortName,
        subtitle = match.league.name,
        scrollFade = true,
        trailing = {
            // Always-present focus target, so the remote works even before any stream is found.
            FocusPill(onClick = { navigator.pop() }) {
                Text("Done", style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = 36.dp).align(Alignment.Center))
            }
        },
    ) {
        PagePivot(0.28f) {
            LazyColumn(
                contentPadding = PaddingValues(top = OverlayContentTop, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                item("banner") {
                    MatchBanner(match, Modifier.padding(horizontal = Spacing.ScreenH)) {
                        if (best != null && match.state != GameState.FINAL) WatchAction(match, best, lineupName(best), onClick = { play(best) }, modifier = Modifier.initialFocus())
                    }
                }
                // The score, quarters and top performers come first: this screen is what you glance at beside the game.
                item("centre") { GameCentreSection(match) }

                when {
                    match.state == GameState.FINAL -> Unit
                    confirmed.isNotEmpty() -> item("sources") {
                        Shelf(if (confirmed.size > 1) "More Ways to Watch" else "Stream Source", icon = Icons.Rounded.Tv, tint = Palette.AccessibleAccent) {
                            items(confirmed.take(24), key = { it.channel.key }) { source ->
                                SourceTile(source, lineupName(source), onClick = { play(source) })
                            }
                        }
                    }
                    lineup.playlists.isNotEmpty() -> item("none") { NoSourcesNote(match, hasPossible = possible.isNotEmpty(), loading = lineup.loading.isNotEmpty() || guide.isLoading) }
                    else -> item("setup") { AddPlaylistNote(onAdd = { navigator.popToRoot(); navigator.select(Tab.Settings) }) }
                }
                if (possible.isNotEmpty() && match.state != GameState.FINAL) item("possible") {
                    Shelf("More Options", icon = Icons.Rounded.Sensors, tint = Palette.TextSecondary) {
                        items(possible.take(24), key = { it.channel.key }) { source ->
                            SourceTile(source, lineupName(source), onClick = { play(source) }, modifier = if (confirmed.isEmpty() && source === possible.first()) Modifier.initialFocus() else Modifier)
                        }
                    }
                }
                if (matchOdds != null) item("odds") { OddsCard(match, matchOdds) }
                if (match.broadcasts.isNotEmpty()) item("broadcasts") { Broadcasts(match.broadcasts) }
                item("info") { MatchInfo(match) }
                if (newsItems.isNotEmpty()) item("news") {
                    Shelf("Related News", icon = Icons.Rounded.Newspaper) {
                        items(newsItems, key = { it.id }) { article ->
                            ArticleCard(article, 380.dp, onClick = { navigator.push(Route.ArticleReader(article)) })
                        }
                    }
                }
            }
        }
    }
}

private fun relevantNews(all: List<Article>, match: Match): List<Article> {
    val needles = listOf(match.home, match.away).flatMap { listOf(it.displayName, it.shortName) }
        .map { it.lowercase() }.filter { it.length >= 4 }.toSet()
    val related = all.filter { article -> (article.headline + " " + article.description).lowercase().let { text -> needles.any(text::contains) } }
    return (related.ifEmpty { all }).take(8)
}

// region Banner

@Composable
internal fun MatchBanner(match: Match, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val spoilerFree = LocalSpoilerFree.current
    val live = match.state == GameState.LIVE && !spoilerFree
    val brush = remember(match.home.colorHex, match.away.colorHex) {
        val base = Palette.Abyss
        fun tint(hex: String?) = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
            ?.let { lerp(base, Color(0xFF000000 or it), 0.55f) } ?: Palette.Navy
        Brush.horizontalGradient(0f to tint(match.away.colorHex), 0.5f to lerp(base, Palette.Background, 0.6f), 1f to tint(match.home.colorHex))
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(256.dp)
            .clip(RoundedCornerShape(Shapes.Xl))
            .background(brush)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Shapes.Xl))
            .padding(horizontal = 56.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BannerTeam(match.away, spoilerFree || match.state == GameState.PRE, Modifier.weight(1f))
        Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (live) LiveBadge()
            Text(
                when {
                    match.state == GameState.PRE -> "VS"
                    spoilerFree -> if (match.state == GameState.FINAL) "Final" else "In progress"
                    else -> match.statusDetail
                },
                style = if (match.state == GameState.PRE) Type.Display else Type.Title3,
                color = if (match.state == GameState.PRE) Color.White.copy(alpha = 0.42f) else Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (match.state == GameState.PRE) Text(Dates.dateTime(match.startMillis), style = Type.BodyStrong, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
            match.live.situation.takeIf { live }?.take(2)?.forEach { Text(it, style = Type.Callout, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center) }
            action?.invoke()
        }
        BannerTeam(match.home, spoilerFree || match.state == GameState.PRE, Modifier.weight(1f))
    }
}

@Composable
private fun BannerTeam(side: TeamSide, hideScore: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamLogo(side.logoUrl, 84.dp)
        Text(side.displayName, style = Type.Title3, color = Color.White, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!hideScore && !side.score.isNullOrBlank()) {
            Text(side.score.orEmpty(), style = Type.ScoreXl, color = if (side.isWinner) Palette.AccessibleAccent else Color.White)
        } else if (!side.record.isNullOrBlank()) {
            Text(side.record.orEmpty(), style = Type.Callout, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

// endregion

// region Sources

/** The one thing most people open this screen to do, always in the same place: start the best confirmed stream. */
@Composable
private fun WatchAction(match: Match, source: RankedSource, name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusPill(onClick = onClick, selected = true, height = 56.dp, modifier = modifier) {
            Row(Modifier.padding(horizontal = 36.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Text(if (match.state == GameState.LIVE) "Watch Live" else "Watch", style = Type.Headline, color = Color.White)
            }
        }
        Text("on $name", style = Type.Caption, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SourceTile(source: RankedSource, name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (badge, color) = when (source.tier) {
        LinkTier.LiveListing, LinkTier.ListingByDescription -> "Playlist EPG" to Palette.Upcoming
        LinkTier.EventChannel -> "Event channel" to Palette.AccessibleAccent
        LinkTier.TeamChannel -> "Team channel" to Palette.AccessibleAccent
        LinkTier.LikelyRights -> "Rights holder" to Palette.Starting
        LinkTier.Coverage -> "Coverage" to Palette.TextSecondary
    }
    FocusCard(
        onClick = onClick,
        modifier = modifier.size(240.dp, 300.dp),
        restingBorder = color.copy(alpha = if (source.isConfirmed) 0.55f else 0.25f),
        glow = color,
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelLogo(source.channel.logoUrl, 72.dp)
            Text(name, style = Type.BodyStrong, color = Palette.TextPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                source.programmeTitle ?: source.channel.group,
                style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Chip(badge, style = if (color == Palette.Upcoming) ChipStyle.Success else if (color == Palette.Starting) ChipStyle.Warning else ChipStyle.Accent)
            Row(
                Modifier.background(Palette.ActionFill, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("Watch", style = Type.Caption, color = Color.White)
            }
        }
    }
}

@Composable
private fun NoSourcesNote(match: Match, hasPossible: Boolean, loading: Boolean) {
    val reason = when {
        loading -> "Checking your playlist and guide…"
        match.startMillis - System.currentTimeMillis() > 48 * 3600_000L -> "Listings appear closer to kickoff."
        hasPossible -> "Only possible matches were found. See More Options below."
        else -> "No listing in your playlist's guide."
    }
    Row(
        Modifier
            .padding(horizontal = Spacing.ScreenH)
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(Shapes.Lg))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Shapes.Lg))
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(Icons.Rounded.SearchOff, null, tint = Palette.TextSecondary, modifier = Modifier.size(34.dp))
        Column {
            Text("No matching streams found", style = Type.Headline, color = Palette.TextPrimary)
            Text(reason, style = Type.Body, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun AddPlaylistNote(onAdd: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = Spacing.ScreenH)
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(Shapes.Lg))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Shapes.Lg))
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(Icons.Rounded.AddLink, null, tint = Palette.AccessibleAccent, modifier = Modifier.size(34.dp))
        Column(Modifier.weight(1f)) {
            Text("Add a playlist to watch", style = Type.Headline, color = Palette.TextPrimary)
            Text("Add an M3U link or stream login in Settings and matching streams appear here.", style = Type.Body, color = Palette.TextSecondary)
        }
        FocusPill(onClick = onAdd, modifier = Modifier.initialFocus()) {
            Text("Open Settings", style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = 30.dp).align(Alignment.Center))
        }
    }
}

// endregion

@Composable
private fun Broadcasts(networks: List<String>) {
    Column(Modifier.padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Broadcasts", icon = Icons.Rounded.Tv)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { networks.forEach { Chip(it, style = ChipStyle.Neutral) } }
    }
}

/** Moneyline for each side (and the draw where the sport has one), from the preferred sportsbook. */
@Composable
private fun OddsCard(match: Match, odds: MatchOdds) {
    Column(Modifier.padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Moneyline", icon = Icons.Rounded.Paid, trailing = "${odds.bookmaker} · to win · − marks the favourite")
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(Shapes.Lg))
                .border(1.dp, Palette.Hairline, RoundedCornerShape(Shapes.Lg))
                .padding(vertical = 20.dp),
        ) {
            OddsColumn(match.away.abbreviation.ifBlank { match.away.shortName }, odds.away, Modifier.weight(1f))
            odds.draw?.let { OddsColumn("Draw", it, Modifier.weight(1f)) }
            OddsColumn(match.home.abbreviation.ifBlank { match.home.shortName }, odds.home, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OddsColumn(label: String, price: Int?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Type.Callout, color = Palette.TextSecondary, maxLines = 1)
        Text(
            price?.let(::formatAmericanOdds) ?: "–",
            style = Type.Title2,
            // Favourites (negative prices) read in the accent; underdogs stay neutral.
            color = if (price != null && price < 0) Palette.AccessibleAccent else Palette.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MatchInfo(match: Match) {
    Column(Modifier.padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Match Info", icon = Icons.Rounded.Info)
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(Shapes.Lg))
                .border(1.dp, Palette.Hairline, RoundedCornerShape(Shapes.Lg)),
        ) {
            InfoRow("League", match.league.name)
            InfoRow("Kickoff", Dates.dateTime(match.startMillis))
            // Before kickoff the status is just the kickoff time again.
            if (match.state != GameState.PRE) InfoRow("Status", if (LocalSpoilerFree.current) "Hidden" else match.statusDetail)
            match.venue?.let { InfoRow("Venue", it) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.BodyStrong, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = Type.Body, color = Palette.TextPrimary, textAlign = TextAlign.End, maxLines = 2)
    }
}
