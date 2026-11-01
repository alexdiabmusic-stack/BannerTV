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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.lineup.Quality
import com.alexdiab.stadiatv.ui.components.ChannelLogo
import com.alexdiab.stadiatv.ui.components.Chip
import com.alexdiab.stadiatv.ui.components.ChipStyle
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Type

val LineupChannelCardWidth: Dp = 270.dp
val LineupChannelCardHeight: Dp = 204.dp

/**
 * A channel as the lineup knows it: the cleaned-up name, the picture quality that will play, and what is on it right
 * now with how far through it is. Tiles built from this are the same everywhere (Home, Live TV), so a channel never
 * looks like two different things.
 */
@Composable
fun LineupChannelCard(
    row: GuideRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val now = row.now
    val quality = row.pick.stream.quality
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(LineupChannelCardWidth, LineupChannelCardHeight),
        restingBorder = if (row.isFavorite) Palette.Accent.copy(alpha = 0.5f) else Palette.Hairline,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChannelLogo(row.pick.channel.logoUrl, 68.dp)
                if (quality != Quality.Sd) Chip(quality.label, style = ChipStyle.Neutral, modifier = Modifier.align(Alignment.TopStart))
                if (row.isFavorite) Icon(Icons.Rounded.Favorite, "Favorite", tint = Palette.Live, modifier = Modifier.align(Alignment.TopEnd).size(22.dp))
            }
            Text(row.entry.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(
                now?.title ?: row.label,
                style = Type.Caption,
                color = if (now != null) Palette.AccessibleAccent else Palette.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (now != null) {
                val progress = remember(now) { now.progress(System.currentTimeMillis()) }
                Box(Modifier.fillMaxWidth().height(4.dp).background(Palette.SurfaceHigh, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Palette.Accent, RoundedCornerShape(2.dp)))
                }
            } else {
                Text("No schedule", style = Type.Caption, color = Palette.TextTertiary, maxLines = 1)
            }
        }
    }
}
