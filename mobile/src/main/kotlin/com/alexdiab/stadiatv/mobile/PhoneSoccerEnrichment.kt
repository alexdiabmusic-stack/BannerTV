package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.alexdiab.stadiatv.domain.*
import java.util.Locale
import kotlin.math.abs

internal fun soccerNumber(value: Double): String = String.format(Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd('.', ',')
internal fun soccerShotLabel(raw: String): String = when (raw) {
    "AttemptSaved" -> "Saved or blocked attempt" // The recording does not split goalkeeper saves from blocks here.
    "Miss" -> "Off target"; "Goal" -> "Goal"; "Post" -> "Woodwork"
    "LeftFoot" -> "Left foot"; "RightFoot" -> "Right foot"; "Head" -> "Header"
    "RegularPlay" -> "Open play"; "FastBreak" -> "Fast break"; "IndividualPlay" -> "Individual play"
    "FromCorner" -> "From corner"; "SetPiece" -> "Set piece"; "Penalty" -> "Penalty"
    else -> raw.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
}

/** Independent failures stay inspectable without a stack of banners consuming the entire small window. */
@Composable
internal fun SoccerResourceNotices(gameKey: String, errors: Map<String, String>, retry: () -> Unit) {
    var expanded by rememberSaveable(gameKey) { mutableStateOf(false) }
    if (errors.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(if (errors.size == 1) errors.values.first() else "${errors.size} match resources couldn't refresh. Accepted data is retained.",
            Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { expanded = true }) { Text("Details") }
        TextButton(onClick = retry) { Text("Retry") }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("Match data status") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            errors.toSortedMap().forEach { (key, value) -> Text("$key · $value", style = MaterialTheme.typography.bodyMedium) }
            Text("Retry and network restoration do not bypass provider access or rate-limit cooldowns.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("Done") } })
}

/** Raw shot geometry stays raw until its coordinate/projection contract is verified. Missing isn't centre-field. */
@Composable
internal fun SoccerShotsScreen(match: Match, detail: SoccerDetail?) {
    var side by rememberSaveable(match.key) { mutableStateOf("All") }
    val shots = detail?.shots.orEmpty().filter { side == "All" || it.teamId == if (side == "Home") match.home.teamId else match.away.teamId }
    ScreenList {
        item("source") {
            SectionTitle("Shots")
            Text("FotMob · reported shot outcomes and expected goals (xG). No location is guessed for a missing coordinate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            detail?.surfaceUpdatedAt?.get("shots")?.let { Text("Checked ${snapshotTime(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ChoiceRow(listOf("All", "Home", "Away"), side) { side = it }
        }
        if (shots.isEmpty()) item("empty") { EmptyState("No reported shots", if (detail?.errors?.containsKey("shots") == true) "The shot resource couldn't refresh. Any previously accepted shots are retained." else "Shots appear only when published by the verified match feed.") }
        items(shots.sortedWith(compareByDescending<SoccerShot> { it.minute ?: -1 }.thenByDescending { it.addedMinute ?: 0 }), key = { it.id }) { shot ->
            PhoneCard {
                val club = if (shot.teamId == match.home.teamId) match.home else match.away
                val clock = shot.minute?.let { "$it${shot.addedMinute?.takeIf { n -> n > 0 }?.let { n -> "+$n" }.orEmpty()}′" }.orEmpty()
                Text(listOf(clock, shot.player?.name ?: club.shortName).filter(String::isNotBlank).joinToString(" · "), fontWeight = FontWeight.Bold)
                Text(listOf(shot.outcome, shot.bodyPart, shot.situation).filterNotNull().filter(String::isNotBlank).joinToString(" · ", transform = ::soccerShotLabel), style = MaterialTheme.typography.bodyMedium)
                Text("${club.shortName} · xG ${shot.expectedGoals?.let(::soccerNumber) ?: "not supplied"}", style = MaterialTheme.typography.bodyMedium)
                val x = shot.x; val y = shot.y
                Text(if (x != null && y != null) "Reported coordinates · x ${soccerNumber(x)}, y ${soccerNumber(y)}" else "Location not supplied",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A signed provider-defined pressure scale, never odds or win probability; exact values remain accessible. */
@Composable
internal fun SoccerPressureCard(match: Match, detail: SoccerDetail) {
    var expanded by rememberSaveable(match.key) { mutableStateOf(false) }
    var page by rememberSaveable(match.key) { mutableIntStateOf(0) }
    val samples = remember(detail.pressure) { detail.pressure.sortedBy { it.minute } }
    val home = MaterialTheme.colorScheme.primary
    val away = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outlineVariant
    PhoneCard {
        SectionTitle("Attacking pressure")
        Text("FotMob · provider-defined scale, not win probability. Positive favors ${match.home.shortName}; negative favors ${match.away.shortName}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        detail.surfaceUpdatedAt["pressure"]?.let { Text("Checked ${snapshotTime(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (samples.isNotEmpty()) Canvas(Modifier.fillMaxWidth().height(144.dp).semantics {
            contentDescription = "Attacking pressure, ${samples.size} reported samples. Use Show reported values for the full accessible list."
        }) {
            val last = maxOf(1.0, samples.last().minute)
            val scale = samples.maxOf { abs(it.value) }.coerceAtLeast(1.0)
            drawLine(axis, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            val barWidth = (size.width / maxOf(90, samples.size) * .65f).coerceIn(1.dp.toPx(), 5.dp.toPx())
            samples.forEach { sample ->
                val x = (sample.minute / last * size.width).toFloat()
                val y = (size.height / 2 - sample.value / scale * (size.height / 2 - 4.dp.toPx())).toFloat()
                drawLine(if (sample.value >= 0) home else away, Offset(x, size.height / 2), Offset(x, y), barWidth)
            }
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide reported values" else "Show reported values") }
        if (expanded) {
            val safePage = page.coerceIn(0, ((samples.size - 1).coerceAtLeast(0)) / 60)
            samples.drop(safePage * 60).take(60).forEach { sample ->
                Text("${soccerNumber(sample.minute)}′ · ${soccerNumber(sample.value)}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (safePage > 0) TextButton(onClick = { page = safePage - 1 }) { Text("Previous values") }
                if ((safePage + 1) * 60 < samples.size) TextButton(onClick = { page = safePage + 1 }) { Text("Next values") }
            }
        }
    }
}
