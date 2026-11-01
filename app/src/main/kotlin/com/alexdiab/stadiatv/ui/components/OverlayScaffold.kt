package com.alexdiab.stadiatv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Tv
import com.alexdiab.stadiatv.ui.theme.Type

/** Space a pushed screen's list leaves at the top so its first item clears the scroll fade under the header. */
val OverlayContentTop = 28.dp

/**
 * Full-screen frame for screens pushed above the tabs. It is opaque (it covers the tab bar) and
 * carries a consistent header: an optional leading image, title, subtitle and trailing content.
 */
@Composable
fun OverlayScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** For screens whose body scrolls under the header: fades that edge, and the list should start [OverlayContentTop] down. */
    scrollFade: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().background(Palette.Background).background(Tv.gradients.screen)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 44.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                leading?.invoke()
                Column(Modifier.weight(1f)) {
                    if (subtitle != null) Text(subtitle.uppercase(), style = Type.Overline, color = Palette.AccessibleAccent, maxLines = 1)
                    Text(title, style = Type.Title1, color = Palette.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                trailing?.invoke()
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                content()
                // Content scrolls under the header; fade it out instead of cutting it with a hard edge.
                if (scrollFade) Box(
                    Modifier
                        .fillMaxWidth()
                        .height(OverlayContentTop)
                        .background(Brush.verticalGradient(listOf(Palette.Background, Color.Transparent))),
                )
            }
        }
    }
}
