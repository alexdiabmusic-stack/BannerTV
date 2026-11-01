package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.LineScore

/** iOS MLBLineScoreView's innings + R/H/E, with team and totals pinned for narrow phone screens. */
@Composable
fun BaseballLineScore(line: LineScore) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val width = (30f * scale).dp; val height = (34f * scale).dp
    PhoneCard {
        Text("Inning by inning", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width((52f * scale.coerceAtMost(1.3f)).dp)) {
                BaseballLineCell("Team", height, bold = false)
                BaseballLineCell(line.awayLabel, height, bold = true)
                BaseballLineCell(line.homeLabel, height, bold = true)
            }
            Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Row { line.periods.forEach { BaseballLineCell(it, height, width) } }
                for ((label, values) in listOf(line.awayLabel to line.away, line.homeLabel to line.home)) Row {
                    line.periods.forEachIndexed { index, inning -> BaseballLineCell(values.getOrNull(index) ?: "–", height, width,
                        description = "$label, inning $inning, ${values.getOrNull(index)?.takeIf { it != "–" } ?: "not played"}") }
                }
            }
            VerticalDivider(Modifier.height(height * 3))
            Column {
                Row { for (label in listOf("R", "H", "E")) BaseballLineCell(label, height, width, bold = label == "R") }
                for ((label, values) in listOf(line.awayLabel to listOf(line.awayTotal, line.awayHits, line.awayErrors), line.homeLabel to listOf(line.homeTotal, line.homeHits, line.homeErrors))) Row {
                    values.forEachIndexed { index, value -> BaseballLineCell(value ?: "–", height, width, bold = index == 0,
                        description = "$label, ${listOf("runs", "hits", "errors")[index]}, ${value?.takeIf { it != "–" } ?: "not reported"}") }
                }
            }
        }
        Text("Swipe innings · Teams and R/H/E stay visible", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BaseballLineCell(value: String, height: Dp, width: Dp? = null, bold: Boolean = false, description: String? = null) {
    Box(Modifier.height(height).then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
        .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description }), contentAlignment = Alignment.Center) {
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
