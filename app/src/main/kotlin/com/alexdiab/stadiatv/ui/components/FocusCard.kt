package com.alexdiab.stadiatv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.ui.theme.Motion
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Tv

/**
 * The one focusable surface everything on screen is built from.
 *
 * All focus animation (scale, glow, ring, lift) is read inside draw/graphics-layer lambdas, so a focus
 * change never recomposes the card's content, only redraws it. That is what keeps D-pad travel across
 * a dense shelf at a steady frame rate on low-end Fire TV hardware.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Shapes.Lg),
    focusedScale: Float = 1.06f,
    ringColor: Color = Color.White,
    restingBorder: Color = Palette.Hairline,
    background: Brush = Tv.gradients.card,
    glow: Color = Palette.Accent,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(Motion.Quick),
        label = "focus",
    )
    val scale = animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = Motion.FocusScale,
        label = "scale",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .drawBehind {
                val p = progress.value
                if (p > 0.01f) {
                    // Soft halo: concentric translucent rounded rects fall off smoothly outward and
                    // never band, whatever the card's aspect ratio.
                    val step = 4.dp.toPx()
                    val base = 16.dp.toPx()
                    for (layer in GLOW_LAYERS downTo 1) {
                        val grow = step * layer
                        drawRoundRect(
                            color = glow.copy(alpha = GLOW_ALPHA * p),
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + grow * 2, size.height + grow * 2),
                            cornerRadius = CornerRadius(base + grow),
                        )
                    }
                }
            }
            .clip(shape)
            .drawBehind { drawRect(background) }
            .drawWithContent {
                drawContent()
                val p = progress.value
                val stroke = (1.5f + 2.5f * p).dp.toPx()
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(
                    outline = outline,
                    color = if (p > 0.02f) ringColor.copy(alpha = 0.35f + 0.65f * p) else restingBorder,
                    style = Stroke(width = if (p > 0.02f) stroke else 1.dp.toPx()),
                )
                if (p > 0.02f) drawOutline(outline, Color.White.copy(alpha = 0.06f * p))
            }
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusChange?.invoke(it.isFocused)
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        content = { content(focused) },
    )
}

/** A text/icon pill button with the same focus language, for actions and chips. */
@Composable
fun FocusPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    height: Dp = 52.dp,
    onFocusChange: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val fill = if (selected) Brush.linearGradient(listOf(Palette.Accent, Palette.ActionFill))
    else Brush.verticalGradient(listOf(Palette.SurfaceElevated, Palette.SurfaceElevated))
    FocusCard(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        focusedScale = 1.05f,
        background = fill,
        onFocusChange = onFocusChange,
        content = content,
    )
}

private const val GLOW_LAYERS = 5
private const val GLOW_ALPHA = 0.006f
