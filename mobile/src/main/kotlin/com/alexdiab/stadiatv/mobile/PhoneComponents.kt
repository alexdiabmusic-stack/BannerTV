package com.alexdiab.stadiatv.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.live.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScreenList(sectionSpacing: Dp = 14.dp, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = PhoneSpace.lg, end = PhoneSpace.lg, top = PhoneSpace.sm, bottom = PhoneSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing), content = content,
    )
}

@Composable
fun PhoneCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(PhoneShape.panel).then(
            if (onClick == null) Modifier else Modifier.heightIn(min = PhoneSpace.touchTarget).clickable(role = Role.Button, onClick = onClick),
        ),
        shape = PhoneShape.panel, color = MaterialTheme.colorScheme.surface,
    ) { Column(Modifier.padding(PhoneSpace.lg), verticalArrangement = Arrangement.spacedBy(PhoneSpace.md), content = content) }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = PhoneSpace.touchTarget)) { Text(action) }
    }
}

@Composable
fun Logo(url: String?, label: String, size: Dp = 42.dp, decorative: Boolean = false) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        Modifier.size(size).clip(PhoneShape.control).then(if (decorative) Modifier.clearAndSetSemantics { } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank() || failed) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.ImageNotSupported,
                        contentDescription = if (decorative) null else "$label image unavailable",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(size * 0.5f),
                    )
                }
            }
        } else AsyncImage(url, contentDescription = if (decorative) null else "$label logo", modifier = Modifier.size(size), contentScale = ContentScale.Fit, onError = { failed = true })
    }
}

@Composable
fun StatusPill(text: String, live: Boolean = false) {
    val colors = if (live) PhoneDesign.colors.live else PhoneDesign.colors.stale
    Surface(shape = PhoneShape.control, color = colors.container) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.foreground, modifier = Modifier.padding(horizontal = PhoneSpace.sm, vertical = PhoneSpace.xs))
    }
}

@Composable
fun EmptyState(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Sensors, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
fun GameCard(match: Match, model: MobileViewModel, hero: Boolean = false, openDetails: Boolean = true) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val sources by model.container.streams.sources.collectAsStateWithLifecycle()
    val confirmed = sources[match.key]?.let { model.container.streams.sourcesFor(match).confirmed.firstOrNull() }
    val hiddenScore = settings.spoilerFreeMode && match.state == GameState.FINAL
    PhoneCard(onClick = if (openDetails) ({ model.open(PhonePage.Game(match)) }) else null) {
        EventRowHeading(match, showLeague = true, followed = settings.involvesFavorite(match))
        EventScoreRows(match, hiddenScore, hero = hero, shortNames = false)
        if (!hiddenScore) NativeFantasyMatchBadge(match, model)
        EventRowFooter(match, model, hiddenScore, confirmed != null, openDetails)
    }
}

/** A scan-friendly score row: imagery belongs to the feature, scores belong in a stable column. */
@Composable
fun CinematicEventRow(match: Match, model: MobileViewModel, modifier: Modifier = Modifier, showLeague: Boolean = true) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val sources by model.container.streams.sources.collectAsStateWithLifecycle()
    val confirmed = sources[match.key]?.let { model.container.streams.sourcesFor(match).confirmed.firstOrNull() }
    val hiddenScore = settings.spoilerFreeMode && match.state == GameState.FINAL
    val followed = settings.involvesFavorite(match)
    val showWatch = match.state == GameState.LIVE && confirmed != null
    Surface(color = MaterialTheme.colorScheme.surface, shape = PhoneShape.panel, modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().clip(PhoneShape.panel)
                .clickable(role = Role.Button, onClickLabel = "Open ${match.name}") { model.open(PhonePage.Game(match)) }
                .padding(PhoneSpace.md),
        ) {
            val stack = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f
            Column(verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
                if (stack) {
                    EventRowHeading(match, showLeague, followed)
                    EventScoreRows(match, hiddenScore, hero = false, shortNames = true)
                    EventRowFooter(match, model, hiddenScore, confirmed != null, openDetails = true)
                } else Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
                        if (match.league.group in setOf(SportGroup.Racing, SportGroup.Golf)) {
                            Text(match.name, style = MaterialTheme.typography.titleMedium)
                        } else match.displaySides.forEach { side ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
                                Logo(side.logoUrl, side.abbreviation, 24.dp, decorative = true)
                                Text(side.shortName.ifBlank { side.displayName }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                if (!hiddenScore && match.state != GameState.PRE) Text(
                                    side.score ?: "–", style = PhoneType.compactScore, textAlign = TextAlign.End,
                                    modifier = Modifier.widthIn(min = 28.dp),
                                )
                            }
                        }
                    }
                    Column(Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(PhoneSpace.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhoneSpace.xs)) {
                            if (followed) Icon(Icons.Rounded.Star, "Followed team", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                            Text(
                                listOfNotNull(match.league.shortName.takeIf { showLeague }, match.state.label.takeUnless { match.state == GameState.PRE }).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (match.state == GameState.LIVE) PhoneDesign.colors.live.foreground else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            when {
                                hiddenScore -> "Score hidden"
                                match.state == GameState.PRE -> listOf(clockTime(match.startMillis), match.statusDetail).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                                else -> match.statusDetail.ifBlank { dateLabel(match.startMillis) }
                            },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (showWatch) Button(
                            onClick = {
                                val current = model.container.streams.sourcesFor(match).confirmed.firstOrNull()
                                if (current != null) model.play(current.channel, match) else model.open(PhonePage.Game(match))
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = PhoneSpace.touchTarget), shape = PhoneShape.control,
                            contentPadding = PaddingValues(horizontal = PhoneSpace.sm, vertical = PhoneSpace.sm),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(PhoneSpace.xs))
                            Text("Watch")
                        }
                    }
                }
                if (!hiddenScore) NativeFantasyMatchBadge(match, model)
            }
        }
    }
}

@Composable
private fun EventRowHeading(match: Match, showLeague: Boolean, followed: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhoneSpace.xs)) {
            if (followed) Icon(Icons.Rounded.Star, "Followed team", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            if (showLeague) Text(match.league.shortName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusPill(
            text = when (match.state) { GameState.LIVE -> "LIVE"; GameState.FINAL -> "FINAL"; GameState.PRE -> clockTime(match.startMillis) },
            live = match.state == GameState.LIVE,
        )
    }
}

@Composable
private fun EventScoreRows(match: Match, hiddenScore: Boolean, hero: Boolean, shortNames: Boolean) {
    if (match.league.group in setOf(SportGroup.Racing, SportGroup.Golf)) {
        Text(match.name, style = if (hero) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
        match.venue?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    for (side in match.displaySides) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhoneSpace.md)) {
            Logo(side.logoUrl, side.abbreviation, if (hero) 44.dp else 28.dp, decorative = true)
            Column(Modifier.weight(1f)) {
                Text(
                    if (shortNames) side.shortName.ifBlank { side.displayName } else side.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (hero) FontWeight.SemiBold else FontWeight.Medium,
                )
                if (hero && !hiddenScore && !side.record.isNullOrBlank()) Text(side.record.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!hiddenScore && match.state != GameState.PRE) Text(
                side.score ?: "–", style = if (hero) PhoneType.score else PhoneType.compactScore,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(min = if (hero) 48.dp else 36.dp),
            )
        }
    }
}

@Composable
private fun EventRowFooter(match: Match, model: MobileViewModel, hiddenScore: Boolean, confirmed: Boolean, openDetails: Boolean) {
    val showWatch = match.state == GameState.LIVE && confirmed
    val phase = if (hiddenScore) "Final score hidden" else match.statusDetail.ifBlank { dateLabel(match.startMillis) }
    val phaseColor = if (match.state == GameState.LIVE) PhoneDesign.colors.live.foreground else MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = LocalDensity.current.fontScale > 1.3f || maxWidth < 260.dp
        val phaseContent: @Composable (Modifier) -> Unit = { modifier ->
            Text(phase, style = MaterialTheme.typography.labelMedium, color = phaseColor, modifier = modifier)
        }
        val actionContent: @Composable () -> Unit = {
            if (showWatch) Button(
                onClick = {
                    // A score feed never establishes availability; recheck the current playlist evidence on activation.
                    val current = model.container.streams.sourcesFor(match).confirmed.firstOrNull()
                    if (current != null) model.play(current.channel, match) else model.open(PhonePage.Game(match))
                },
                modifier = Modifier.heightIn(min = PhoneSpace.touchTarget),
                contentPadding = PaddingValues(horizontal = PhoneSpace.md, vertical = PhoneSpace.sm), shape = PhoneShape.control,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(PhoneSpace.xs))
                Text("Watch")
            } else if (openDetails) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (stack && showWatch) Column(verticalArrangement = Arrangement.spacedBy(PhoneSpace.xs)) {
            phaseContent(Modifier.fillMaxWidth())
            actionContent()
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
            phaseContent(Modifier.weight(1f))
            actionContent()
        }
    }
}

@Composable
fun ChannelRow(channel: Channel, model: MobileViewModel, onClick: () -> Unit = { model.play(channel) }) {
    val settings by model.container.preferences.settings.collectAsState()
    val guide by model.container.guide.state.collectAsState()
    val now = remember(channel.key, guide.revision) { model.container.guide.nowAndNext(channel).first }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Logo(channel.logoUrl, channel.name, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(channel.canonicalName ?: channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now?.title ?: channel.group.ifBlank { "Live channel" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) LinearProgressIndicator(progress = { now.progress(System.currentTimeMillis()) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            val starred = channel.key in settings.favoriteChannelIds
            IconButton(onClick = { model.container.preferences.toggleFavoriteChannel(channel.key) }) {
                Icon(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (starred) "Remove favorite" else "Favorite channel", tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ChannelGroupMenu(channel, model)
        }
    }
}

@Composable
fun StoryCard(article: Article, model: MobileViewModel, ignoreFilters: Boolean = false) {
    val context = LocalContext.current
    val library by model.library.state.collectAsState()
    val saved = library.savedStories.any { it.id == article.id }
    if (!ignoreFilters && (article.id in library.hiddenStoryIds || article.source in library.mutedNewsSources)) return
    var menu by remember { mutableStateOf(false) }
    PhoneCard(onClick = { model.open(PhonePage.Reader(article)) }) {
        article.imageUrl?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(article.source.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            IconButton(onClick = { model.library.toggleStory(article) }, modifier = Modifier.size(48.dp)) { Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (saved) "Unsave article" else "Save article") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "Article actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Hide this story") }, onClick = { menu = false; model.library.update { it.copy(hiddenStoryIds = it.hiddenStoryIds + article.id) } })
                    DropdownMenuItem(text = { Text("Mute ${article.source}") }, onClick = { menu = false; model.library.update { it.copy(mutedNewsSources = it.mutedNewsSources + article.source) } })
                    DropdownMenuItem(text = { Text("Open publisher") }, onClick = { menu = false; openBrowser(context, article.url) })
                }
            }
        }
        Text(article.headline, style = MaterialTheme.typography.titleMedium)
        if (article.description.isNotBlank()) Text(article.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private data class Loaded<T>(val value: T? = null, val error: String? = null, val loading: Boolean = true)

@Composable
fun <T> AsyncSection(key: Any?, load: suspend () -> T, content: @Composable (T) -> Unit) {
    var attempt by remember(key) { mutableIntStateOf(0) }
    val latestLoader by rememberUpdatedState(load)
    val result by produceState<Loaded<T>>(Loaded(), key, attempt) {
        value = Loaded()
        try { value = Loaded(value = withContext(Dispatchers.IO) { latestLoader() }, loading = false) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { value = Loaded(error = "Couldn't load this information. Check your connection and try again.", loading = false) }
    }
    when {
        result.loading -> Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        result.error != null -> EmptyState("Unable to refresh", result.error!!, "Try again", { attempt++ })
        else -> result.value?.let { content(it) }
    }
}

fun clockTime(millis: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

/** Saved data may be days old: a clock alone must not make yesterday's snapshot look current. */
fun snapshotTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return SimpleDateFormat(if (day.format(Date(millis)) == day.format(Date(now))) "h:mm a" else "MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(millis))
}
fun dateLabel(millis: Long): String = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(millis))
fun openBrowser(context: android.content.Context, url: String?) {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
    if (uri.scheme !in setOf("http", "https")) return
    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { }
}
