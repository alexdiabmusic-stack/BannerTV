package com.alexdiab.stadiatv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Type

/** A rounded, scrollable list panel for master/detail screens. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SidePanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(Palette.Surface, RoundedCornerShape(Shapes.Xl))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Shapes.Xl))
            .verticalScroll(rememberScrollState())
            .focusRestorer()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = Type.Overline, color = Palette.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
        content()
    }
}

/**
 * One row in a [SidePanel]. Focusing a row selects it, so the detail pane previews as the user moves
 * down the list (the tvOS sidebar behaviour), with no extra click.
 */
@Composable
fun SideRow(
    title: String,
    selected: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = Palette.TextSecondary,
    icon: ImageVector? = null,
    logoUrl: String? = null,
) {
    FocusCard(
        onClick = onFocused,
        modifier = modifier.fillMaxWidth().height(if (subtitle != null) 68.dp else 60.dp),
        shape = RoundedCornerShape(Shapes.Md),
        focusedScale = 1.03f,
        background = if (selected) Brush.horizontalGradient(listOf(Palette.Accent.copy(alpha = 0.92f), Palette.ActionFill.copy(alpha = 0.92f)))
        else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
        restingBorder = Color.Transparent,
        onFocusChange = { if (it) onFocused() },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                logoUrl != null -> TeamLogo(logoUrl, 34.dp)
                icon != null -> Icon(icon, null, tint = if (selected) Color.White else Palette.AccessibleAccent, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (selected) Type.BodyStrong else Type.Body,
                    color = if (selected) Color.White else Palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = Type.Caption,
                        color = if (selected) Color.White.copy(alpha = 0.75f) else subtitleColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
