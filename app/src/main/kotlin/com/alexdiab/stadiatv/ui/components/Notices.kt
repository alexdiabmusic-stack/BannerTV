package com.alexdiab.stadiatv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates

/** The scores on screen are from an earlier fetch: say when, and let the viewer try again. */
@Composable
fun StaleNote(updatedAt: Long, retry: () -> Unit, modifier: Modifier = Modifier, inset: Dp = Spacing.ScreenH) {
    Row(
        modifier
            .padding(horizontal = inset)
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Palette.Starting.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Rounded.WifiOff, null, tint = Palette.Starting, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text("Couldn't refresh scores", style = Type.BodyStrong, color = Palette.TextPrimary)
            Text(
                if (updatedAt > 0) "Showing games from ${Dates.relative(updatedAt)}. They may be out of date." else "Showing the last games we loaded. They may be out of date.",
                style = Type.Callout, color = Palette.TextSecondary,
            )
        }
        FocusPill(onClick = retry, height = 48.dp) {
            Row(Modifier.padding(horizontal = 24.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Refresh, null, tint = Palette.TextPrimary, modifier = Modifier.size(24.dp))
                Text("Retry", style = Type.BodyStrong, color = Palette.TextPrimary)
            }
        }
    }
}

@Composable
fun InfoNote(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(horizontal = Spacing.ScreenH)
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 26.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(30.dp))
        Column {
            Text(title, style = Type.BodyStrong, color = Palette.TextPrimary)
            Text(subtitle, style = Type.Callout, color = Palette.TextSecondary, maxLines = 1)
        }
    }
}


/** A nudge with one button: something the viewer can do now that would improve this screen. */
@Composable
fun ActionNote(icon: ImageVector, title: String, subtitle: String, action: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(horizontal = Spacing.ScreenH)
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Palette.Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(icon, null, tint = Palette.AccessibleAccent, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.BodyStrong, color = Palette.TextPrimary)
            Text(subtitle, style = Type.Callout, color = Palette.TextSecondary, maxLines = 1)
        }
        FocusPill(onClick = onClick, selected = true, height = 52.dp) {
            Text(action, style = Type.BodyStrong, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(horizontal = 28.dp).align(Alignment.Center))
        }
    }
}
