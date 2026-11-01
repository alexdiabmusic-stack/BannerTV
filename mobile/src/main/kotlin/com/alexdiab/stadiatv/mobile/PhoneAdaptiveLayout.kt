package com.alexdiab.stadiatv.mobile

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val LocalPhoneFolds = staticCompositionLocalOf { emptyList<PhoneFold>() }

@Composable
internal fun PhoneWindowLayout(activity: Activity, content: @Composable () -> Unit) {
    // The Activity owns this flow. Never retain it in the application/ViewModel across recreation.
    val features = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            info.displayFeatures.filterIsInstance<FoldingFeature>().map { feature ->
                val b = feature.bounds
                PhoneFold(PhonePaneRect(b.left, b.top, b.right, b.bottom),
                    if (feature.orientation == FoldingFeature.Orientation.VERTICAL) PhoneFoldAxis.Vertical else PhoneFoldAxis.Horizontal,
                    feature.isSeparating, feature.occlusionType == FoldingFeature.OcclusionType.FULL)
            }
        }
    }
    val folds by features.collectAsStateWithLifecycle(emptyList())
    CompositionLocalProvider(LocalPhoneFolds provides folds, content = content)
}

@Composable
private fun rememberPhonePanePlan(width: Int, height: Int, origin: IntOffset, hasSecondary: Boolean, expanded: Boolean = true): PhonePanePlan {
    val density = LocalDensity.current
    val folds = LocalPhoneFolds.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return with(density) {
        phonePanePlan(width, height, origin.x, origin.y, folds, hasSecondary,
            expandedWidth = if (expanded) 700.dp.roundToPx() else Int.MAX_VALUE,
            // Font scaling must not turn two nominally wide panes into unusable narrow controls.
            minPaneWidth = (320.dp * fontScale.coerceAtLeast(1f)).roundToPx(),
            minPaneHeight = 180.dp.roundToPx(), gap = 24.dp.roundToPx(), rtl = rtl)
    }
}

/** A guide remains virtualized; only its bounded detail is placed alongside it on usable windows. */
@Composable
internal fun PhoneInlineDetailLayout(
    enabled: Boolean, primary: @Composable (inline: Boolean) -> Unit, secondary: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {
        val position = it.positionInWindow(); origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
    }) {
        val plan = rememberPhonePanePlan(constraints.maxWidth, constraints.maxHeight, origin, enabled, enabled)
        Layout(content = {
            Box { primary(plan.secondary != null) }
            Box { if (plan.secondary != null) secondary() }
        }, modifier = Modifier.fillMaxSize()) { children, constraints ->
            val a = plan.primary
            val b = plan.secondary
            val first = children[0].measure(Constraints.fixed(a.width, a.height))
            val second = children[1].measure(Constraints.fixed(b?.width ?: 0, b?.height ?: 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                first.place(a.left, a.top)
                if (b != null) second.place(b.left, b.top)
            }
        }
    }
}

@Composable
internal fun PhonePlayerLayout(
    modifier: Modifier, landscape: Boolean, pip: Boolean, attachedMatch: Boolean, gamePanel: Boolean, companionKey: String,
    video: @Composable () -> Unit, companion: @Composable (automatic: Boolean) -> Unit,
) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    val folds = LocalPhoneFolds.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val saved = rememberSaveableStateHolder()
    BoxWithConstraints(modifier.onGloballyPositioned {
        val position = it.positionInWindow(); origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
    }) {
        val result = with(density) { phonePlayerPanePlan(constraints.maxWidth, constraints.maxHeight, landscape, pip, attachedMatch, gamePanel,
            origin.x, origin.y, folds, expandedWidth = 700.dp.roundToPx(), minPaneWidth = (320.dp * fontScale.coerceAtLeast(1f)).roundToPx(),
            minPaneHeight = 180.dp.roundToPx(), gap = 24.dp.roundToPx(), portraitMin = 300.dp.roundToPx(),
            portraitMax = 420.dp.roundToPx(), landscapePanelWidth = 300.dp.roundToPx(), rtl = rtl) }
        Layout(content = {
            // Unconditional first child. Never reparent, key by size, or duplicate the AndroidView.
            Box { video() }
            Box { if (result.panes.secondary != null) saved.SaveableStateProvider(companionKey) { companion(result.automaticCompanion) } }
        }, modifier = Modifier.fillMaxSize()) { children, constraints ->
            val a = result.panes.primary
            val b = result.panes.secondary
            val first = children[0].measure(Constraints.fixed(a.width, a.height))
            val second = children[1].measure(Constraints.fixed(b?.width ?: 0, b?.height ?: 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                first.place(a.left, a.top)
                if (b != null) second.place(b.left, b.top)
            }
        }
    }
}

/** Stable children: resizing changes only measurement/placement, not tab or scroll composition. */
@Composable
internal fun PhoneGameCentreLayout(
    expanded: Boolean, hasSecondary: Boolean, primary: @Composable () -> Unit, secondary: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {
        val position = it.positionInWindow(); origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
    }) {
        val plan = rememberPhonePanePlan(constraints.maxWidth, constraints.maxHeight, origin, hasSecondary, expanded)
        Layout(content = {
            Column(Modifier.verticalScroll(rememberScrollState())) { primary() }
            Column { secondary() }
        }, modifier = Modifier.fillMaxSize()) { children, constraints ->
            val rect = plan.primary
            val secondRect = plan.secondary
            if (secondRect != null) {
                val first = children[0].measure(Constraints.fixed(rect.width, rect.height))
                val second = children[1].measure(Constraints.fixed(secondRect.width, secondRect.height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    first.place(rect.left, rect.top); second.place(secondRect.left, secondRect.top)
                }
            } else {
                val maxHeader = if (hasSecondary) (rect.height * .65f).roundToInt() else rect.height
                val first = children[0].measure(Constraints(maxWidth = rect.width, maxHeight = maxHeader))
                val second = children[1].measure(Constraints.fixed(rect.width, if (hasSecondary) (rect.height - first.height).coerceAtLeast(0) else 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    first.place(rect.left, rect.top); second.place(rect.left, rect.top + first.height)
                }
            }
        }
    }
}

internal class PhoneFeedScope {
    internal val entries = mutableListOf<Pair<Any, @Composable () -> Unit>>()
    fun item(key: Any? = null, content: @Composable () -> Unit) { entries += (key ?: entries.size) to content }
    fun <T> items(values: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
        values.forEach { value -> item(key(value)) { content(value) } }
    }
}

/** Bounded overview feeds, like iOS AdaptiveTwoPane: both columns share one scroll position. */
@Composable
internal fun PhoneAdaptiveFeed(
    sectionSpacing: Dp = 20.dp, primary: PhoneFeedScope.() -> Unit, secondary: PhoneFeedScope.() -> Unit,
) {
    val first = PhoneFeedScope().apply(primary).entries
    val second = PhoneFeedScope().apply(secondary).entries
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {
        val position = it.positionInWindow(); origin = IntOffset(position.x.roundToInt(), position.y.roundToInt())
    }) {
        val plan = rememberPhonePanePlan(constraints.maxWidth, constraints.maxHeight, origin, second.isNotEmpty())
        val a = plan.primary
        val b = plan.secondary
        val viewport = if (b == null) a else PhonePaneRect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        Layout(modifier = Modifier.fillMaxSize(), content = {
            Box(Modifier.verticalScroll(scroll)) {
                Layout(content = {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (b != null || second.isEmpty()) 28.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
                        first.forEach { (id, body) -> key(id) { body() } }
                    }
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = if (b != null || first.isEmpty()) 8.dp else 0.dp, bottom = if (second.isEmpty()) 0.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
                        second.forEach { (id, body) -> key(id) { body() } }
                    }
                }) { children, inner ->
                    val left = children[0].measure(Constraints.fixedWidth(a.width))
                    val right = children[1].measure(Constraints.fixedWidth(b?.width ?: a.width))
                    val gap = with(density) { sectionSpacing.roundToPx() }
                    val totalHeight = if (b != null) maxOf(left.height, right.height) else left.height + (if (first.isEmpty() || second.isEmpty()) 0 else gap) + right.height
                    layout(inner.maxWidth, totalHeight) {
                        left.place(a.left - viewport.left, 0)
                        right.place(b?.let { it.left - viewport.left } ?: 0, if (b != null) 0 else left.height + (if (first.isEmpty() || second.isEmpty()) 0 else gap))
                    }
                }
            }
        }) { children, outer ->
            val content = children.single().measure(Constraints.fixed(viewport.width, viewport.height))
            layout(outer.maxWidth, outer.maxHeight) { content.place(viewport.left, viewport.top) }
        }
    }
}

/** Unbounded live/day lists stay lazy. Expanded Android panes scroll independently. */
@Composable
internal fun PhoneAdaptiveLazyFeed(
    sectionSpacing: Dp = 18.dp, primary: PhoneFeedScope.() -> Unit, secondary: PhoneFeedScope.() -> Unit,
) {
    val first = PhoneFeedScope().apply(primary).entries.map { (id, content) -> "primary:$id" to content }
    val second = PhoneFeedScope().apply(secondary).entries.map { (id, content) -> "secondary:$id" to content }
    val compactState = rememberLazyListState()
    val primaryState = rememberLazyListState()
    val secondaryState = rememberLazyListState()
    val saved = rememberSaveableStateHolder()
    var previousIds by remember { mutableStateOf(emptySet<String>()) }
    val ids = (first + second).map { it.first }.toSet()
    LaunchedEffect(ids) { (previousIds - ids).forEach(saved::removeState); previousIds = ids }
    PhoneInlineDetailLayout(enabled = second.isNotEmpty(), primary = { inline ->
        LazyColumn(Modifier.fillMaxSize(), state = if (inline) primaryState else compactState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
            items(if (inline) first else first + second, key = { it.first }) { (id, body) -> saved.SaveableStateProvider(id) { body() } }
        }
    }, secondary = {
        LazyColumn(Modifier.fillMaxSize(), state = secondaryState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
            items(second, key = { it.first }) { (id, body) -> saved.SaveableStateProvider(id) { body() } }
        }
    })
}
