package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*

/** iPhone-style home-first choice; both teams remain visible on a wide Android window. */
@Composable
internal fun SoccerLineupsScreen(match: Match, detail: SoccerDetail?, model: MobileViewModel, nativeEpl: Boolean) {
    var home by rememberSaveable(match.key) { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { SoccerLineupList(match, detail, true, nativeEpl, model) }
            VerticalDivider()
            Box(Modifier.weight(1f)) { SoccerLineupList(match, detail, false, nativeEpl, model) }
        } else Column {
            val choices = listOf("Home · ${match.home.abbreviation.ifBlank { match.home.shortName }}", "Away · ${match.away.abbreviation.ifBlank { match.away.shortName }}")
            ChoiceRow(choices, if (home) choices.first() else choices.last()) { home = it == choices.first() }
            Box(Modifier.weight(1f)) { SoccerLineupList(match, detail, home, nativeEpl, model) }
        }
    }
}

@Composable
private fun SoccerLineupList(match: Match, detail: SoccerDetail?, home: Boolean, nativeEpl: Boolean, model: MobileViewModel) {
    val side = if (home) match.home else match.away
    val lineup = detail?.lineups?.firstOrNull { it.home == home }
    fun open(player: SportPlayer) { phoneSoccerAthlete(player, nativeEpl)?.let { model.open(PhonePage.PlayerDetail(match.league, it)) } }
    ScreenList {
        item("team") { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Logo(side.logoUrl, side.abbreviation, 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(side.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                lineup?.formation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } }
        detail?.surfaceUpdatedAt?.get("lineups")?.let { at -> item("time") { Text("Lineups · ${snapshotTime(at)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (lineup == null || lineup.players.isEmpty()) item("empty") {
            if (detail?.overviewNotPublished == true) EmptyState("${side.shortName} lineup not published", "The provider hasn't published this fixture's detailed lineup resource yet.")
            else if (detail?.errors?.containsKey("lineups") == true) EmptyState("${side.shortName} lineup unavailable", "The provider couldn't check this lineup. Previously announced players are retained when available.")
            else EmptyState("${side.shortName} lineup not announced", "Official starting players appear when the provider publishes them.")
        }
        lineup?.let { team ->
            val reportedCoordinates = team.players.any { it.starter && it.membershipKnown && it.pitchPosition != null }
            if (reportedCoordinates) item("reported-pitch") { SoccerReportedFormationPitch(team) }
            else if (team.hasPitch) item("pitch") { SoccerFormationPitch(team, nativeEpl, ::open) }
            if (team.players.any { it.rating != null }) item("rating-source") {
                Text("FotMob Rating · provider rating, not an app-calculated score. Match-player identities are separate from official club-roster profiles.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            team.manager?.let { manager -> item("manager") { Text("Manager · $manager", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            val groups = listOf("Starting players" to team.players.filter { it.membershipKnown && it.starter },
                "Substitutes" to team.players.filter { it.membershipKnown && !it.starter },
                "Announced players · role not supplied" to team.players.filter { !it.membershipKnown })
            for ((title, players) in groups) if (players.isNotEmpty()) {
                item("title-$title") { SectionTitle(title) }
                items(players, key = { "$title:${it.player.id}" }) { slot ->
                    ListItem(headlineContent = { Text(slot.player.name + if (slot.captain) " (C)" else "") },
                        supportingContent = { Column {
                            if (slot.player.position.isNotBlank()) Text(slot.player.position)
                            slot.rating?.let { Text("${slot.ratingSource ?: "Provider rating"} · ${soccerNumber(it)}") }
                        } },
                        leadingContent = { Text(slot.player.number.ifBlank { "–" }, Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.heightIn(min = 56.dp).clickable(enabled = phoneSoccerAthlete(slot.player, nativeEpl) != null) { open(slot.player) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background))
                }
            }
            if (!team.hasPitch && !reportedCoordinates) item("no-geometry") { Text("Formation positions weren't supplied. Starting players are listed without guessed pitch coordinates.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The provider's normalized vertical layout, not formation-string row heuristics or measured metres. */
@Composable
private fun SoccerReportedFormationPitch(team: SoccerLineup) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reported formation positions · FotMob Rating", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF164B31))) {
            val availableWidth = maxWidth - 52.dp
            val availableHeight = maxHeight - 80.dp
            Canvas(Modifier.matchParentSize()) {
                val lines = Color.White.copy(alpha = .4f)
                drawRect(lines, Offset(8.dp.toPx(), 8.dp.toPx()), Size(size.width - 16.dp.toPx(), size.height - 16.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawLine(lines, Offset(8.dp.toPx(), size.height / 2), Offset(size.width - 8.dp.toPx(), size.height / 2), 1.dp.toPx())
                drawCircle(lines, size.width * .13f, center, style = Stroke(1.dp.toPx()))
            }
            team.players.filter { it.starter && it.membershipKnown && it.pitchPosition != null }.forEach { slot ->
                val position = slot.pitchPosition!!
                Column(Modifier.offset(x = availableWidth * position.x, y = availableHeight * (1f - position.y))
                    .width(52.dp).heightIn(min = 80.dp).semantics {
                        contentDescription = "${slot.player.name}, number ${slot.player.number.ifBlank { "unknown" }}${slot.rating?.let { ", FotMob Rating ${soccerNumber(it)}" }.orEmpty()}"
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.size(30.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Text(slot.player.number.ifBlank { "–" }, color = Color.Black, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    Text(slot.player.name.substringAfterLast(' '), style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    slot.rating?.let { Text(soccerNumber(it), style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1) }
                }
            }
        }
        Text("Full names and ratings are listed below. Players without a reported position are not placed on this diagram.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Decorative field markings do not imply measured player coordinates; only reported tactical rows place players. */
@Composable
private fun SoccerFormationPitch(team: SoccerLineup, nativeEpl: Boolean, open: (SportPlayer) -> Unit) {
    val rows = team.players.filter { it.membershipKnown && it.starter && it.row != null && it.column != null }.groupBy { it.row!! }.toSortedMap().values.toList().asReversed()
    val extraTextHeight = 20.dp * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val minimumHeight = (76.dp + extraTextHeight) * rows.size + 24.dp * (rows.size - 1).coerceAtLeast(0) + 48.dp
    val fieldHeight = maxOf(maxWidth / .68f, minimumHeight)
    Box(Modifier.fillMaxWidth().height(fieldHeight).clip(RoundedCornerShape(18.dp)).background(Color(0xFF164B31))) {
        Canvas(Modifier.matchParentSize()) {
            for (stripe in 0..7) if (stripe % 2 == 0) drawRect(Color.White.copy(alpha = .045f), Offset(0f, size.height * stripe / 8f), Size(size.width, size.height / 8f))
            val inset = 10.dp.toPx(); val width = size.width - 2 * inset; val height = size.height - 2 * inset
            val lines = Color.White.copy(alpha = .5f); val stroke = 1.2.dp.toPx()
            drawRect(lines, Offset(inset, inset), Size(width, height), style = Stroke(stroke))
            drawLine(lines, Offset(inset, size.height / 2), Offset(size.width - inset, size.height / 2), stroke)
            drawCircle(lines, minOf(width * .135f, height * .12f), center, style = Stroke(stroke))
            drawCircle(lines, 2.dp.toPx(), center)
            for (bottom in listOf(false, true)) {
                val areaHeight = height * .16f; val areaWidth = width * .58f
                drawRect(lines, Offset((size.width - areaWidth) / 2, if (bottom) size.height - inset - areaHeight else inset), Size(areaWidth, areaHeight), style = Stroke(stroke))
                val goalHeight = height * .06f; val goalWidth = width * .26f
                drawRect(lines, Offset((size.width - goalWidth) / 2, if (bottom) size.height - inset - goalHeight else inset), Size(goalWidth, goalHeight), style = Stroke(stroke))
            }
        }
        Column(Modifier.fillMaxSize().padding(vertical = 24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.sortedBy { it.column }.forEach { slot ->
                        Column(Modifier.width(64.dp).heightIn(min = 76.dp).clickable(enabled = phoneSoccerAthlete(slot.player, nativeEpl) != null) { open(slot.player) }
                            .semantics { contentDescription = listOfNotNull(slot.player.number.takeIf(String::isNotBlank)?.let { "Number $it" }, slot.player.name,
                                slot.player.position.takeIf(String::isNotBlank), "captain".takeIf { slot.captain }).joinToString(", ") },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(Modifier.size(40.dp), shape = CircleShape, color = Color.White, contentColor = Color(0xFF123B2A)) {
                                Box(contentAlignment = Alignment.Center) { Text(slot.player.number.ifBlank { "–" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                            }
                            Text(slot.player.name.substringAfterLast(' ') + if (slot.captain) " (C)" else "", color = Color.White,
                                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    }
}
