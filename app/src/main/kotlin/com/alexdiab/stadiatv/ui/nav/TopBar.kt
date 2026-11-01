package com.alexdiab.stadiatv.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.R
import com.alexdiab.stadiatv.ui.theme.Motion
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.delay

val TopBarInset = 136.dp

/**
 * How far a tab's content starts below the screen top. Screens inside the Sports hub sit under its sub-navigation row,
 * so the hub supplies a smaller value than the default.
 */
val LocalTopInset = androidx.compose.runtime.compositionLocalOf { TopBarInset }

/**
 * Floating tab bar in the tvOS style. Moving focus across tabs previews each one after a short
 * debounce, so scrubbing quickly through the bar doesn't build and tear down every screen on the way.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    barFocusRequesters: Map<Tab, FocusRequester>,
    contentFocusRequester: FocusRequester,
    onBarFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusedTab by remember { mutableStateOf<Tab?>(null) }
    LaunchedEffect(focusedTab) {
        val tab = focusedTab ?: return@LaunchedEffect
        if (tab != selected) {
            delay(PREVIEW_DEBOUNCE_MILLIS)
            onSelect(tab)
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenH, vertical = 26.dp)
            .height(76.dp)
            // Hand focus to the screen below explicitly; automatic geometry search does not reliably cross from this
            // floating bar into a lazily composed page. On the whole bar, not just the tabs: Settings is an icon beside
            // the clock, and without this the only way into Settings could not be left by pressing Down.
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown) {
                    runCatching { contentFocusRequester.requestFocus() }.getOrDefault(false)
                } else false
            },
    ) {
        Brand(Modifier.align(Alignment.CenterStart))
        Row(
            Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(50))
                .background(Color(0xE6151922))
                .border(1.dp, Palette.Hairline, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 7.dp)
                .focusRestorer(barFocusRequesters.getValue(selected))
                .onFocusChanged { onBarFocusChanged(it.hasFocus) },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.primary.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == selected,
                    focusRequester = barFocusRequesters.getValue(tab),
                    // Clearing on blur cancels a pending preview, so a passing focus never switches tabs.
                    onFocusChange = { focused ->
                        if (focused) focusedTab = tab else if (focusedTab == tab) focusedTab = null
                    },
                    onClick = { onSelect(tab) },
                )
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Tab.entries.filter { it.secondary }.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == selected,
                    focusRequester = barFocusRequesters.getValue(tab),
                    onFocusChange = { focused -> if (focused) focusedTab = tab else if (focusedTab == tab) focusedTab = null },
                    onClick = { onSelect(tab) },
                    iconOnly = true,
                )
            }
            Clock()
        }
    }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Image(
            painter = painterResource(R.drawable.app_icon_raster),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Text("BannerTV", style = Type.Title3, color = Palette.TextPrimary)
    }
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    Text(Dates.time(now), style = Type.Digits, color = Palette.TextSecondary, modifier = modifier)
}

@Composable
private fun TabItem(
    tab: Tab,
    selected: Boolean,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    iconOnly: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        targetValue = when {
            focused -> Color.White
            selected -> Color.White.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        animationSpec = tween(Motion.Quick),
        label = "tabFill",
    )
    val content by animateColorAsState(
        targetValue = when {
            focused -> Color(0xFF0B0E15)
            selected -> Palette.TextPrimary
            else -> Palette.TextSecondary
        },
        animationSpec = tween(Motion.Quick),
        label = "tabContent",
    )
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, Motion.FocusScale, label = "tabScale")
    Row(
        Modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(fill)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (iconOnly) 12.dp else 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = content, modifier = Modifier.size(if (iconOnly) 28.dp else 24.dp))
        if (!iconOnly) Text(tab.label, style = Type.BodyStrong, color = content, maxLines = 1)
    }
}

private const val PREVIEW_DEBOUNCE_MILLIS = 170L
