package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.sports.NativeBasketballDuration
import java.util.Locale

/** iOS half-court geometry and 2/3-point markers, plus accessible, lazy attempt details. */
@Composable
fun BasketballShotChart(match: Match, data: GameCentre) {
    var side by rememberSaveable(match.key) { mutableIntStateOf(0) }
    var period by rememberSaveable(match.key) { mutableIntStateOf(0) }
    var selectedId by rememberSaveable(match.key) { mutableStateOf<String?>(null) }
    val shots = remember(data.shots, side, period) { data.shots.filter {
        (side == 0 || it.home == (side == 2)) && (period == 0 || it.period == period)
    } }
    val onCourt = remember(shots) { shots.filter(PhoneBasketballCourt::onCourt) }
    val beyond = shots.count(PhoneBasketballCourt::beyondHalf)
    val outside = shots.size - onCourt.size - beyond
    val selected = shots.firstOrNull { it.id == selectedId }
    val madeColor = MaterialTheme.colorScheme.primary
    val missedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val regulation = data.basketball?.regulationPeriods ?: 4
    val periods = data.shots.map { it.period }.filter { it > 0 }.distinct().sorted()
    val sides = listOf("Both teams", match.away.abbreviation, match.home.abbreviation)
    fun periodLabel(value: Int) = if (value == 0) "All periods" else NativeBasketballDuration.period(value, regulation)
    ScreenList {
        item { ChoiceRow(sides, sides[side]) { label -> side = sides.indexOf(label).coerceAtLeast(0); selectedId = null } }
        item { ChoiceRow(listOf("All periods") + periods.map(::periodLabel), periodLabel(period)) { label ->
            period = periods.firstOrNull { periodLabel(it) == label } ?: 0; selectedId = null
        } }
        if (data.shotSource == null) item { EmptyState("Spatial feed unavailable", "Real locations are requested from ${match.league.name}. Missing coordinates are never estimated from a shot's distance.") }
        else item { PhoneCard {
            Text("Shot chart", style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(data.shotSource, data.basketball?.surfaceUpdatedAt?.get("plays")?.let(::snapshotTime)).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${shots.count { it.made }} made of ${shots.size} attempts", style = MaterialTheme.typography.titleSmall)
            fun position(shot: GameShot, width: Float, height: Float): Offset = PhoneBasketballCourt.point(shot.x, shot.y).let { Offset(it.u * width, it.v * height) }
            Canvas(Modifier.fillMaxWidth().aspectRatio(50f / 47f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .semantics { contentDescription = PhoneBasketballCourt.summary(shots) + " Larger markers are three-pointers. Every reported attempt is listed below." }
                .pointerInput(onCourt) { detectTapGestures { tap -> selectedId = onCourt.minByOrNull {
                    (position(it, size.width.toFloat(), size.height.toFloat()) - tap).getDistance()
                }?.takeIf { (position(it, size.width.toFloat(), size.height.toFloat()) - tap).getDistance() <= 24.dp.toPx() }?.id } }) {
                val stroke = 1.dp.toPx()
                val unit = size.width / 50f
                fun point(x: Float, y: Float): Offset = PhoneBasketballCourt.point(x, y).let { Offset(it.u * size.width, it.v * size.height) }
                fun courtPath(points: List<PhoneBasketballCourt.Point>) = Path().apply { points.forEachIndexed { index, p ->
                    if (index == 0) moveTo(p.u * size.width, p.v * size.height) else lineTo(p.u * size.width, p.v * size.height)
                } }
                drawRect(line, style = Stroke(stroke))
                val hoop = point(0f, 0f)
                drawCircle(line, .75f * unit, hoop, style = Stroke(stroke))
                drawLine(line, point(-3f, -1.25f), point(3f, -1.25f), stroke)
                drawRect(line, point(-8f, 13.75f), Size(16 * unit, 19 * unit), style = Stroke(stroke))
                drawCircle(line, 6 * unit, point(0f, 13.75f), style = Stroke(stroke))
                drawPath(courtPath(PhoneBasketballCourt.restrictedArea()), line, style = Stroke(stroke))
                drawPath(courtPath(PhoneBasketballCourt.threePointLine()), line, style = Stroke(stroke))
                onCourt.forEach { shot ->
                    val p = position(shot, size.width, size.height)
                    val radius = PhoneBasketballCourt.markerRadius(shot.value).dp.toPx()
                    drawCircle(if (shot.made) madeColor else missedColor, radius, p)
                    if (shot.id == selectedId) drawCircle(madeColor, radius + 3.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                }
            }
            if (beyond > 0) Text("$beyond ${if (beyond == 1) "shot" else "shots"} beyond half court", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (outside > 0) Text("$outside ${if (outside == 1) "attempt" else "attempts"} outside the court viewport", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("● Made", color = madeColor, style = MaterialTheme.typography.labelMedium)
                Text("● Missed", color = missedColor, style = MaterialTheme.typography.labelMedium)
            }
            Text("Larger marker: 3-point · Smaller marker: 2-point", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            selected?.let { Text(basketballShotDescription(it, match, regulation), style = MaterialTheme.typography.bodyMedium) }
            if (shots.isEmpty()) Text("No reported shot locations for this selection.", style = MaterialTheme.typography.bodyMedium)
        } }
        if (shots.isNotEmpty()) item { SectionTitle("Reported attempts · ${shots.size}") }
        // No silent 50-row cutoff. Lazy rows retain every actual attempt, including off-canvas heaves.
        items(shots.asReversed(), key = { it.id }) { shot ->
            Column(Modifier.fillMaxWidth().clickable { selectedId = shot.id }.semantics(mergeDescendants = true) {}.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${if (shot.made) "Made" else "Missed"}${shot.value?.takeIf { it == 2 || it == 3 }?.let { " · $it-point" }.orEmpty()} · ${shot.player.ifBlank { if (shot.home) match.home.abbreviation else match.away.abbreviation }}", fontWeight = FontWeight.SemiBold)
                Text(basketballShotDescription(shot, match, regulation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun basketballShotDescription(shot: GameShot, match: Match, regulation: Int): String = listOfNotNull(
    if (shot.home) match.home.abbreviation else match.away.abbreviation,
    shot.period.takeIf { it > 0 }?.let { NativeBasketballDuration.period(it, regulation) },
    shot.distanceFeet?.takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.US, "%.1f ft", it) },
    shot.description.takeIf(String::isNotBlank),
    "Beyond half court".takeIf { PhoneBasketballCourt.beyondHalf(shot) },
).joinToString(" · ")
