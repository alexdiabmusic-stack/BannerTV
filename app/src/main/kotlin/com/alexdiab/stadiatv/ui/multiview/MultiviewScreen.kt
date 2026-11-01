package com.alexdiab.stadiatv.ui.multiview

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.data.SavedMultiview
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.playback.AspectMode
import com.alexdiab.stadiatv.playback.MultiviewCapacity
import com.alexdiab.stadiatv.playback.MultiviewLayout
import com.alexdiab.stadiatv.playback.PlaybackController
import com.alexdiab.stadiatv.playback.PlaybackUi
import com.alexdiab.stadiatv.playback.restore
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.Spinner
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.player.ChannelsPanel
import com.alexdiab.stadiatv.ui.player.OptionHeading
import com.alexdiab.stadiatv.ui.player.OptionRow
import com.alexdiab.stadiatv.ui.player.PanelFrame
import com.alexdiab.stadiatv.ui.player.VideoSurface
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Type
import kotlinx.coroutines.flow.MutableStateFlow

private enum class PipCorner(val alignment: Alignment) {
    BottomEnd(Alignment.BottomEnd), BottomStart(Alignment.BottomStart), TopStart(Alignment.TopStart), TopEnd(Alignment.TopEnd);

    fun next() = entries[(ordinal + 1) % entries.size]
}

private sealed interface Panel {
    data object Add : Panel
    data object Layouts : Panel
    data class Replace(val channel: Channel) : Panel
    data class Stream(val channel: Channel) : Panel
}

/**
 * Watch several channels at once: side by side, stacked, picture-in-picture, 3-up or 4-up. One stream
 * has the sound (OK on a tile selects it); Menu or a long press opens that stream's options.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MultiviewScreen(seed: List<Channel>) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val lineup by container.playlists.state.collectAsStateWithLifecycle()

    val session = remember {
        val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        MultiviewSession(context, MultiviewCapacity.maxStreams(lowRam), onAdded = container.watchHistory::record)
            .also { session -> seed.forEach(session::add) }
    }
    var layout by remember { mutableStateOf(MultiviewLayout.defaultFor(seed.size.coerceAtLeast(1))) }
    var maximizedKey by remember { mutableStateOf<String?>(null) }
    var corner by remember { mutableStateOf(PipCorner.BottomEnd) }
    var panel by remember { mutableStateOf<Panel?>(null) }

    val channels = session.channels
    val options = MultiviewLayout.optionsFor(session.capacity)
    val maximized = channels.firstOrNull { it.key == maximizedKey }
    val slots = minOf(layout.capacity, session.capacity)
    val visible = if (maximized != null) listOf(maximized) else channels.take(slots)
    // Free slots show an "add a stream" tile, so any layout can be filled from the grid itself.
    val gridSlots: List<Channel?> = if (maximized != null) visible else visible + List((slots - visible.size).coerceAtLeast(0)) { null }

    DisposableEffect(session) { onDispose { session.releaseAll() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.pauseAll() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { session.resumeAll() }
    LaunchedEffect(visible.joinToString { it.key }, session.audioKey) { session.sync(visible) }
    LaunchedEffect(channels.size) {
        if (channels.size > layout.capacity) layout = MultiviewLayout.defaultFor(channels.size)
    }

    BackHandler {
        when {
            panel != null -> panel = null
            maximized != null -> maximizedKey = null
            else -> navigator.pop()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Header(
            count = channels.size,
            capacity = session.capacity,
            layout = layout,
            options = options,
            maximized = maximized != null,
            onClose = { navigator.pop() },
            onLayout = { layout = it },
            onUnmaximize = { maximizedKey = null },
            onMoveInset = { corner = corner.next() },
            onLayouts = { panel = Panel.Layouts },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val tile: @Composable (Channel?, Modifier, Boolean) -> Unit = { channel, modifier, first ->
                if (channel == null) {
                    AddTile(onClick = { panel = Panel.Add }, modifier = modifier, requestFocus = first)
                } else {
                    StreamTile(
                        channel = channel,
                        controller = session.controllerFor(channel),
                        listening = channel.key == session.audioKey,
                        compact = layout == MultiviewLayout.Inset && channel.key != visible.firstOrNull()?.key,
                        requestFocus = first,
                        onClick = { session.listenTo(channel) },
                        onMenu = { panel = Panel.Stream(channel) },
                        modifier = modifier,
                    )
                }
            }
            Grid(layout, gridSlots, corner, tile)

            SidePanel(visible = panel != null, modifier = Modifier.align(Alignment.CenterEnd)) {
                when (val current = panel) {
                    Panel.Add -> ChannelPicker(
                        title = "Add a stream",
                        candidates = lineup.channels.filter { !session.contains(it) },
                        onPick = { session.add(it) },
                        onClose = { panel = null },
                    )
                    is Panel.Replace -> ChannelPicker(
                        title = "Replace ${current.channel.name}",
                        candidates = lineup.channels.filter { !session.contains(it) },
                        onPick = { session.replace(current.channel, it) },
                        onClose = { panel = null },
                    )
                    is Panel.Stream -> StreamMenu(
                        channel = current.channel,
                        listening = current.channel.key == session.audioKey,
                        isMaximized = maximized?.key == current.channel.key,
                        onListen = { session.listenTo(current.channel); panel = null },
                        onMaximize = { maximizedKey = if (maximized?.key == current.channel.key) null else current.channel.key; panel = null },
                        onReplace = { panel = Panel.Replace(current.channel) },
                        onRemove = { session.remove(current.channel); panel = null },
                    )
                    Panel.Layouts -> LayoutsPanel(
                        slots = settings.multiviewSlots,
                        lineup = lineup.channels,
                        onSave = { slot ->
                            val saved = SavedMultiview(channels.map { it.key }, layout.name, session.audioKey)
                            container.preferences.update { it.copy(multiviewSlots = it.multiviewSlots + (slot to saved)) }
                            panel = null
                        },
                        onRestore = { slot ->
                            settings.multiviewSlots[slot]?.restore(lineup.channels, session.capacity)?.let { restored ->
                                maximizedKey = null
                                session.setAll(restored.channels, restored.audioKey)
                                layout = restored.layout
                            }
                            panel = null
                        },
                    )
                    null -> Unit
                }
            }
        }
    }
}

/** Slides a panel in from the right edge. */
@Composable
private fun SidePanel(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = modifier,
    ) { content() }
}

// region Header

@Composable
private fun Header(
    count: Int,
    capacity: Int,
    layout: MultiviewLayout,
    options: List<MultiviewLayout>,
    maximized: Boolean,
    onClose: () -> Unit,
    onLayout: (MultiviewLayout) -> Unit,
    onUnmaximize: () -> Unit,
    onMoveInset: () -> Unit,
    onLayouts: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FocusPill(onClick = onClose) {
            Row(Modifier.padding(horizontal = 24.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Palette.TextPrimary, modifier = Modifier.size(24.dp))
                Text("Close", style = Type.BodyStrong, color = Palette.TextPrimary)
            }
        }
        Column(Modifier.padding(start = 8.dp)) {
            Text("Multiview", style = Type.Title3, color = Palette.TextPrimary, maxLines = 1)
            Text("$count of $capacity streams", style = Type.Caption, color = Palette.TextSecondary, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (maximized) {
            FilterChip("Back to grid", false, onUnmaximize)
        } else {
            options.forEach { option -> FilterChip(option.label, option == layout, { onLayout(option) }) }
            if (layout == MultiviewLayout.Inset && count > 1) FilterChip("Move inset", false, onMoveInset)
        }
        FilterChip("Layouts", false, onLayouts)
    }
}

// endregion

// region Grid

@Composable
private fun Grid(
    layout: MultiviewLayout,
    slots: List<Channel?>,
    corner: PipCorner,
    tile: @Composable (Channel?, Modifier, Boolean) -> Unit,
) {
    val gap = Modifier.padding(2.dp)
    when {
        slots.size <= 1 -> Box(Modifier.fillMaxSize()) { tile(slots.firstOrNull(), gap.fillMaxSize(), true) }
        layout == MultiviewLayout.SideBySide -> Row(Modifier.fillMaxSize()) {
            slots.forEachIndexed { index, channel -> tile(channel, gap.weight(1f).fillMaxHeight(), index == 0) }
        }
        layout == MultiviewLayout.Stacked -> Column(Modifier.fillMaxSize()) {
            slots.forEachIndexed { index, channel -> tile(channel, gap.weight(1f).fillMaxWidth(), index == 0) }
        }
        layout == MultiviewLayout.Inset -> Box(Modifier.fillMaxSize()) {
            tile(slots[0], gap.fillMaxSize(), true)
            tile(slots[1], Modifier.align(corner.alignment).padding(28.dp).fillMaxWidth(INSET_WIDTH).aspectRatio(16f / 9f), false)
        }
        layout == MultiviewLayout.ThreeUp -> Row(Modifier.fillMaxSize()) {
            tile(slots[0], gap.weight(1.6f).fillMaxHeight(), true)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                slots.drop(1).forEach { channel -> tile(channel, gap.weight(1f).fillMaxWidth(), false) }
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            slots.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    pair.forEachIndexed { column, channel -> tile(channel, gap.weight(1f).fillMaxHeight(), row == 0 && column == 0) }
                }
            }
        }
    }
}

private const val INSET_WIDTH = 0.34f

// endregion

// region Tiles

/** Focus ring and key handling shared by stream tiles and the "add" tile. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileFrame(
    onClick: () -> Unit,
    onMenu: () -> Unit,
    requestFocus: Boolean,
    modifier: Modifier,
    background: Color = Color.Black,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .initialFocus(requestFocus)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) { onMenu(); true } else false
            }
            .background(background)
            .drawWithContent {
                drawContent()
                if (focused) drawRect(Palette.Accent, style = Stroke(width = 6.dp.toPx()))
            }
            .combinedClickable(onClick = onClick, onLongClick = onMenu),
    ) { content() }
}

private val NoPlayback = MutableStateFlow(PlaybackUi())

@Composable
private fun StreamTile(
    channel: Channel,
    controller: PlaybackController?,
    listening: Boolean,
    compact: Boolean,
    requestFocus: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier,
) {
    val playback by (controller?.ui ?: NoPlayback).collectAsStateWithLifecycle()
    TileFrame(onClick, onMenu, requestFocus, modifier) {
        if (controller != null) VideoSurface(controller, AspectMode.Fit)
        when {
            playback.errorMessage != null -> Text(
                "This stream isn't available",
                style = if (compact) Type.Caption else Type.Callout,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            !playback.firstFrameRendered || playback.showsBufferingIndicator -> Spinner(Modifier.align(Alignment.Center), size = if (compact) 28.dp else 44.dp)
        }
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xB3000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (listening) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                contentDescription = if (listening) "Playing sound" else "Muted",
                tint = if (listening) Palette.AccessibleAccent else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(if (compact) 16.dp else 22.dp),
            )
            if (!compact) Text(channel.name, style = Type.Caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit, requestFocus: Boolean, modifier: Modifier) {
    TileFrame(onClick, onMenu = onClick, requestFocus = requestFocus, modifier = modifier, background = Palette.Surface) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Add, null, tint = Palette.AccessibleAccent, modifier = Modifier.size(48.dp))
            Text("Add a stream", style = Type.BodyStrong, color = Palette.TextSecondary)
        }
    }
}

// endregion

// region Panels

@Composable
private fun ChannelPicker(
    title: String,
    candidates: List<Channel>,
    onPick: (Channel) -> Unit,
    onClose: () -> Unit,
) {
    if (candidates.isEmpty()) {
        PanelFrame(title) { Text("No other channels to choose from.", style = Type.Body, color = Color.White.copy(alpha = 0.6f)) }
    } else {
        ChannelsPanel(current = null, channels = candidates, onSelect = onPick, onClose = onClose, title = title)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StreamMenu(
    channel: Channel,
    listening: Boolean,
    isMaximized: Boolean,
    onListen: () -> Unit,
    onMaximize: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    PanelFrame("Stream", subtitle = channel.name) {
        LazyColumn(Modifier.fillMaxSize().focusRestorer(), contentPadding = PaddingValues(bottom = 80.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("listen") { OptionRow("Listen to this stream", listening, onListen, Modifier.initialFocus()) }
            item("maximize") { OptionRow(if (isMaximized) "Back to the grid" else "Fill the screen", false, onMaximize) }
            item("replace") { OptionRow("Replace with another channel", false, onReplace) }
            item("remove") { OptionRow("Remove from multiview", false, onRemove) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LayoutsPanel(
    slots: Map<String, SavedMultiview>,
    lineup: List<Channel>,
    onSave: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    val names = remember(lineup) { lineup.associateBy { it.key } }
    PanelFrame("Layouts", subtitle = "Save what is on screen, or bring a layout back") {
        LazyColumn(Modifier.fillMaxSize().focusRestorer(), contentPadding = PaddingValues(bottom = 80.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (slot in SLOTS) {
                val saved = slots[slot]
                val summary = saved?.channelKeys?.mapNotNull { names[it]?.name }?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                item("h-$slot") { OptionHeading("Slot $slot") }
                item("save-$slot") {
                    OptionRow("Save current layout here", false, { onSave(slot) }, if (slot == SLOTS.first()) Modifier.initialFocus() else Modifier, detail = summary ?: "Empty")
                }
                if (summary != null) item("restore-$slot") { OptionRow("Restore this layout", false, { onRestore(slot) }) }
            }
        }
    }
}

private val SLOTS = listOf("1", "2", "3", "4")

// endregion
