package com.alexdiab.stadiatv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

/** Selectable filter pill used for league, team and day pickers. */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    logoUrl: String? = null,
    count: Int? = null,
    /** The colour of the count when the chip is not selected: red suits live counts, a quiet grey suits plain totals. */
    countColor: androidx.compose.ui.graphics.Color = Palette.Live,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(26.dp),
        focusedScale = 1.06f,
        background = if (selected) Brush.linearGradient(listOf(Palette.Accent, Palette.ActionFill))
        else Brush.verticalGradient(listOf(Palette.Surface, Palette.Surface)),
        restingBorder = if (selected) Palette.Accent else Palette.Hairline,
        onFocusChange = onFocusChange,
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (logoUrl != null) TeamLogo(logoUrl, 28.dp)
            else if (icon != null) Icon(icon, null, tint = if (selected) androidx.compose.ui.graphics.Color.White else Palette.TextSecondary, modifier = Modifier.size(24.dp))
            Text(
                label,
                style = Type.BodyStrong,
                color = if (selected) androidx.compose.ui.graphics.Color.White else Palette.TextSecondary,
                maxLines = 1,
            )
            if (count != null && count > 0) {
                Text("$count", style = Type.Caption, color = if (selected) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f) else countColor)
            }
        }
    }
}

/** A horizontally scrolling row of chips that restores focus to the last-chosen chip when re-entered. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    startPadding: Dp = Spacing.ScreenH,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxWidth().focusRestorer(),
        contentPadding = PaddingValues(start = startPadding, end = Spacing.ScreenH, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
