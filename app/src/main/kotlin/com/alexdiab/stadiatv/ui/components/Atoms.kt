package com.alexdiab.stadiatv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

// region Badges and chips

enum class ChipStyle { Neutral, Accent, Live, Success, Warning }

@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    style: ChipStyle = ChipStyle.Neutral,
    icon: ImageVector? = null,
) {
    val (fill, content) = when (style) {
        ChipStyle.Neutral -> Palette.SurfaceHigh to Palette.TextSecondary
        ChipStyle.Accent -> Palette.Accent.copy(alpha = 0.16f) to Palette.AccessibleAccent
        ChipStyle.Live -> Palette.LiveFill to Color.White
        ChipStyle.Success -> Palette.Upcoming.copy(alpha = 0.16f) to Palette.Upcoming
        ChipStyle.Warning -> Palette.Starting.copy(alpha = 0.16f) to Palette.Starting
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(fill)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(text, style = Type.Caption, color = content, maxLines = 1)
    }
}

/** Pulsing LIVE badge. The pulse is read inside the graphics layer, so it never recomposes. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier, label: String = "LIVE") {
    val transition = rememberInfiniteTransition(label = "live")
    val pulse = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.LiveFill)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .graphicsLayer { alpha = pulse.value }
                .background(Color.White, CircleShape),
        )
        Text(label, style = Type.Caption, color = Color.White, maxLines = 1)
    }
}

// endregion

// region Imagery

/** Team crest. Shows a quiet shield until the image arrives, so layouts never jump. */
@Composable
fun TeamLogo(url: String?, size: Dp, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        var loaded by remember(url) { mutableStateOf(false) }
        if (!loaded) {
            Icon(
                Icons.Rounded.Shield,
                contentDescription = null,
                tint = Palette.TextTertiary.copy(alpha = 0.55f),
                modifier = Modifier.size(size * 0.62f),
            )
        }
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (dimmed) 0.5f else 1f),
            )
        }
    }
}

/** Channel or network logo on a soft tile; falls back to a TV glyph. */
@Composable
fun ChannelLogo(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(Shapes.Md))
            .background(Palette.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        var loaded by remember(url) { mutableStateOf(false) }
        if (!loaded) Icon(Icons.Rounded.Tv, null, tint = Palette.AccessibleAccent, modifier = Modifier.size(size * 0.46f))
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size * 0.1f),
            )
        }
    }
}

// endregion

// region Loading

/** Animated sheen for skeleton placeholders. Driven from the draw phase only. */
@Composable
fun Modifier.shimmer(): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset = transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "offset",
    )
    return this.drawWithContent {
        drawContent()
        val width = size.width
        val start = width * offset.value
        drawRect(
            Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                startX = start,
                endX = start + width * 0.6f,
            ),
        )
    }
}

@Composable
fun SkeletonCard(width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width, height)
            .clip(RoundedCornerShape(Shapes.Lg))
            .background(Palette.Surface)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Shapes.Lg))
            .shimmer(),
    )
}

@Composable
fun SkeletonShelf(title: String, cardWidth: Dp = 360.dp, cardHeight: Dp = 196.dp, count: Int = 5) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .padding(horizontal = Spacing.ScreenH)
                .size(220.dp, 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Surface)
                .shimmer(),
        )
        Row(
            Modifier.padding(horizontal = Spacing.ScreenH),
            horizontalArrangement = Arrangement.spacedBy(ShelfSpacing),
        ) { repeat(count) { SkeletonCard(cardWidth, cardHeight) } }
    }
}

// endregion

// region Headers and states

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Palette.AccessibleAccent,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        Text(title, style = Type.Title3, color = Palette.TextPrimary, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(trailing, style = Type.Callout, color = Palette.TextSecondary, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Shelf(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Palette.AccessibleAccent,
    trailing: String? = null,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier) {
        SectionHeader(title, Modifier.padding(horizontal = Spacing.ScreenH), icon, tint, trailing)
        PivotScroll(startOffset = Spacing.ScreenH - 24.dp) {
            LazyRow(
                state = state,
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = Spacing.ScreenH - 0.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(ShelfSpacing),
                content = content,
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 120.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(Palette.Surface)
                .border(1.dp, Palette.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(52.dp)) }
        Text(title, style = Type.Title2, color = Palette.TextPrimary, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Text(
                subtitle,
                style = Type.Body,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                maxLines = 4,
                modifier = Modifier.width(900.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

// endregion

// region Spinner / async

/** Lightweight indeterminate spinner. The rotation is applied in the graphics layer (no recomposition). */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 56.dp, color: Color = Palette.AccessibleAccent) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val rotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    androidx.compose.foundation.Canvas(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation.value },
    ) {
        val stroke = size.toPx() * 0.1f
        drawArc(
            color = color.copy(alpha = 0.18f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 95f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

@Composable
fun CenteredSpinner(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Spinner()
        if (label != null) {
            Spacer(Modifier.height(18.dp))
            Text(label, style = Type.Headline, color = Palette.TextSecondary)
        }
    }
}

/** Circular player or athlete portrait with a quiet fallback. */
@Composable
fun Headshot(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Palette.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        var loaded by remember(url) { mutableStateOf(false) }
        if (!loaded) {
            Icon(
                Icons.Rounded.Person,
                null,
                tint = Palette.TextTertiary,
                modifier = Modifier.size(size * 0.58f),
            )
        }
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// endregion
