package com.alexdiab.stadiatv.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.alexdiab.stadiatv.lineup.LineupEntry
import com.alexdiab.stadiatv.lineup.Quality
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Type

/**
 * One channel in the on-now list, as in the design: a channel tile, what is on and what kind of programme it is, and
 * how far through it is. OK plays; holding OK opens the channel's actions.
 */
@Composable
fun GuideRowCard(row: GuideRow, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val now = remember(row.now, row.pick) { System.currentTimeMillis() }
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(96.dp).alpha(if (row.isHidden) 0.5f else 1f),
        shape = RoundedCornerShape(18.dp),
        focusedScale = 1.012f,
        restingBorder = if (row.isFavorite) Palette.Accent.copy(alpha = 0.55f) else Palette.Hairline,
        onFocusChange = { if (it) onFocus() },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ChannelBox(row.entry, row.pick.channel.logoUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.now?.title ?: if (row.pick.guideId == null) row.entry.name else "No listing",
                    style = Type.Headline,
                    color = if (row.now != null) Palette.TextPrimary else Palette.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(row.label.ifEmpty { row.entry.name }, style = Type.Callout, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.isFavorite) Icon(Icons.Rounded.Favorite, null, tint = Palette.Live, modifier = Modifier.size(24.dp))
            if (row.isHidden) Icon(Icons.Rounded.VisibilityOff, null, tint = Palette.TextTertiary, modifier = Modifier.size(24.dp))
            QualityTag(row.pick.stream.quality)
            Progress(row, now, Modifier.width(250.dp))
        }
    }
}

@Composable
private fun QualityTag(quality: Quality) {
    if (quality == Quality.Sd) return
    Text(
        quality.label, style = Type.Caption, color = Palette.AccessibleAccent,
        modifier = Modifier.background(Palette.AccessibleAccent.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun Progress(row: GuideRow, now: Long, modifier: Modifier = Modifier) {
    val current = row.now
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        if (current != null) {
            val left = ((current.endMillis - now) / 60_000L).coerceAtLeast(0)
            Text(
                if (left >= 60) "${left / 60} h ${left % 60} min left" else "$left min left",
                style = Type.Caption, color = Palette.TextSecondary, maxLines = 1,
            )
            Box(Modifier.fillMaxWidth().height(6.dp).background(Palette.SurfaceHigh, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(current.progress(now)).fillMaxHeight().background(Palette.Accent, RoundedCornerShape(3.dp)))
            }
        } else if (row.next != null) {
            Text("Next ${com.alexdiab.stadiatv.util.Dates.time(row.next?.startMillis ?: now)}", style = Type.Caption, color = Palette.TextTertiary, maxLines = 1)
        }
    }
}

/** The channel's tile: its logo when the provider has one, otherwise its name set as a wordmark. */
@Composable
fun ChannelBox(entry: LineupEntry, logoUrl: String?, modifier: Modifier = Modifier) {
    var loaded by remember(logoUrl) { mutableStateOf(false) }
    Box(
        modifier.width(150.dp).fillMaxHeight().background(Palette.SurfaceHigh, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                wordmark(entry), style = Type.BodyStrong, color = Palette.TextPrimary, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (!logoUrl.isNullOrEmpty()) {
            AsyncImage(
                model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize().padding(10.dp),
            )
        }
    }
}

private fun wordmark(entry: LineupEntry): String = entry.name
