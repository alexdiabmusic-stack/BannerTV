package com.alexdiab.stadiatv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.theme.Motion
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Type

@Composable
fun SettingsHeading(title: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, null, tint = Palette.AccessibleAccent, modifier = Modifier.size(26.dp))
        Text(title, style = Type.Title3, color = Palette.TextPrimary)
    }
}

/** One settings line: icon, title and subtitle on the left, a control (value, switch, chevron) on the right. */
@Composable
fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Palette.AccessibleAccent,
    trailing: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (subtitle != null) 92.dp else 76.dp),
        shape = RoundedCornerShape(Shapes.Lg),
        focusedScale = 1.015f,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 26.dp).align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (icon != null) {
                Box(Modifier.size(48.dp).background(Palette.SurfaceHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (destructive) Palette.Live else iconTint, modifier = Modifier.size(28.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.Headline, color = if (destructive) Palette.Live else Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = Type.Callout, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun ValueTrailing(value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(value, style = Type.Body, color = Palette.TextSecondary, maxLines = 1)
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary, modifier = Modifier.size(26.dp))
    }
}

@Composable
fun ChevronTrailing() = Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextTertiary, modifier = Modifier.size(28.dp))

@Composable
fun SwitchTrailing(checked: Boolean) {
    val track = animateColorAsState(if (checked) Palette.Accent else Palette.SurfaceHigh, tween(Motion.Quick), label = "track")
    val offset = animateDpAsState(if (checked) 26.dp else 0.dp, tween(Motion.Quick), label = "thumb")
    Box(Modifier.width(60.dp).height(34.dp).background(track.value, CircleShape).padding(3.dp)) {
        // The lambda overload reads the animated value during layout only, so the thumb slides without recomposing.
        Box(Modifier.offset { IntOffset(offset.value.roundToPx(), 0) }.size(28.dp).background(Color.White, CircleShape))
    }
}

@Composable
fun SettingsToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null) {
    SettingsRow(title, onClick = { onChange(!checked) }, modifier = modifier, subtitle = subtitle, icon = icon, trailing = { SwitchTrailing(checked) })
}
