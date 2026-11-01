package com.alexdiab.stadiatv.ui.livetv

import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.live.Programme
import com.alexdiab.stadiatv.ui.components.ChannelLogo
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val PerMinute: Dp = 5.dp
private val ChannelColumn = 330.dp
private val RowHeight = 78.dp
private val TimeHeaderHeight = 52.dp
private const val WindowHours = 14

/** One visible portion of a channel's schedule. A null programme is a real, focusable "No information" gap. */
internal data class TimelineSegment(val startMillis: Long, val endMillis: Long, val programme: Programme?)

/** Pure timeline layout used by the screen and tests. It clips overlaps and fills every guide gap. */
internal object GuideTimelineLogic {
    fun segments(programmes: List<Programme>, windowStart: Long, windowEnd: Long): List<TimelineSegment> {
        if (windowEnd <= windowStart) return emptyList()
        val result = ArrayList<TimelineSegment>()
        var cursor = windowStart
        for (programme in programmes) {
            if (programme.endMillis <= windowStart) continue
            if (programme.startMillis >= windowEnd) break
            val start = maxOf(programme.startMillis, cursor, windowStart)
            val end = minOf(programme.endMillis, windowEnd)
            if (end <= start) continue
            if (start > cursor) result += TimelineSegment(cursor, start, null)
            result += TimelineSegment(start, end, programme)
            cursor = end
        }
        if (cursor < windowEnd) result += TimelineSegment(cursor, windowEnd, null)
        return result
    }
}

/**
 * Cable-style EPG: information and channel preview at the top, fixed channels down the left, and one synchronized time
 * grid across every row. The schedule cells—not decorative cards—own D-pad focus, so left/right always follows time and
 * up/down always follows the channel lineup.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuideTimeline(
    rows: List<GuideRow>,
    guideRevision: Int,
    selectedRow: GuideRow?,
    selectedProgramme: Programme?,
    onFocus: (GuideRow, Programme?) -> Unit,
    onPlay: (GuideRow) -> Unit,
    onActions: (GuideRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalContainer.current
    // Leave half an hour of context to the left of the red "now" line, like a set-top-box guide.
    val windowStart = remember { floorToHalfHour(System.currentTimeMillis() - 30 * 60_000L) }
    val windowEnd = windowStart + WindowHours * 3600_000L
    val scroll: ScrollState = rememberScrollState()
    val totalWidth = PerMinute * (WindowHours * 60)
    val configuration = LocalConfiguration.current
    @Suppress("DEPRECATION")
    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) configuration.locales[0] else configuration.locale
    val now by produceState(initialValue = System.currentTimeMillis(), guideRevision) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val displayedRow = selectedRow ?: rows.firstOrNull()
    val displayedProgramme = selectedProgramme ?: displayedRow?.now

    Column(modifier.fillMaxSize()) {
        GuideProgrammeHeader(displayedRow, displayedProgramme, now)
        Row(
            Modifier
                .fillMaxWidth()
                .height(TimeHeaderHeight)
                .padding(horizontal = Spacing.ScreenH),
        ) {
            Row(
                Modifier
                    .width(ChannelColumn)
                    .fillMaxHeight()
                    .background(Palette.SurfaceElevated)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    SimpleDateFormat("EEE, MMM d", locale).format(Date(now)).uppercase(locale),
                    style = Type.Overline,
                    color = Palette.TextSecondary,
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll).clipToBounds()) {
                TimelineRuler(windowStart, totalWidth, now)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    val programmes = remember(row.key, guideRevision) { row.ref?.let { container.guide.programmesOf(it) }.orEmpty() }
                    Row(Modifier.fillMaxWidth().height(RowHeight)) {
                        GuideChannelCell(
                            row = row,
                            number = index + 1,
                            onFocus = { onFocus(row, row.now) },
                            onPlay = { onPlay(row) },
                            onActions = { onActions(row) },
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .horizontalScroll(scroll)
                                .clipToBounds()
                                .drawBehind {
                                    val halfHour = PerMinute.toPx() * 30
                                    var x = halfHour - (scroll.value % halfHour)
                                    while (x < size.width) {
                                        drawLine(Palette.HairlineStrong, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                        x += halfHour
                                    }
                                },
                        ) {
                            ProgrammeRow(
                                row = row,
                                programmes = programmes,
                                windowStart = windowStart,
                                windowEnd = windowEnd,
                                totalWidth = totalWidth,
                                now = now,
                                onFocus = onFocus,
                                onPlay = onPlay,
                            )
                        }
                    }
                }
            }
            // Marker is an overlay, not another composable per row. Moving time therefore redraws one line only.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = Spacing.ScreenH + ChannelColumn, end = Spacing.ScreenH)
                    .drawWithContent {
                        drawContent()
                        val x = ((now - windowStart) / 60_000f) * PerMinute.toPx() - scroll.value
                        if (x in 0f..size.width) {
                            drawLine(Palette.Live, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
                            drawCircle(Palette.Live, 5.dp.toPx(), Offset(x, 4.dp.toPx()))
                        }
                    },
            )
        }
    }
}

@Composable
private fun GuideProgrammeHeader(row: GuideRow?, programme: Programme?, now: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(154.dp)
            .padding(horizontal = Spacing.ScreenH, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            Modifier
                .width(246.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(Palette.Navy, Palette.Abyss))),
            contentAlignment = Alignment.Center,
        ) {
            if (row != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChannelLogo(row.pick.channel.logoUrl, 70.dp)
                    Text(row.entry.name, style = Type.Caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp))
                }
                if (programme?.isOn(now) == true) {
                    Text(
                        "LIVE",
                        style = Type.Overline,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Palette.LiveFill, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            if (row == null) {
                Text("Choose a channel", style = Type.Title3, color = Palette.TextPrimary)
                Text("Browse programmes with the D-pad and press OK to watch.", style = Type.Body, color = Palette.TextSecondary)
                return@Column
            }
            Text(programme?.title ?: "No programme information", style = Type.Title3, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = programme?.let { "${Dates.time(it.startMillis)} – ${Dates.time(it.endMillis)}" }
            Text(
                listOfNotNull(row.entry.name, time, row.pick.stream.quality.label).joinToString("  ·  "),
                style = Type.Callout,
                color = Palette.AccessibleAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = programme?.let { listOfNotNull(it.subtitle, it.description).joinToString(" — ") }.orEmpty().replace(Regex("\\s+"), " ").trim()
            if (detail.isNotEmpty()) Text(detail, style = Type.Body, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else Text("Press OK to watch. Hold OK on the channel for favourites and guide options.", style = Type.Body, color = Palette.TextTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun TimelineRuler(windowStart: Long, totalWidth: Dp, now: Long) {
    Box(Modifier.width(totalWidth).fillMaxHeight().background(Palette.SurfaceElevated)) {
        Row(Modifier.fillMaxSize()) {
            repeat(WindowHours * 2) { slot ->
                val slotTime = windowStart + slot * 30 * 60_000L
                Box(
                    Modifier
                        .width(PerMinute * 30)
                        .fillMaxHeight()
                        .drawBehind { drawLine(Palette.HairlineStrong, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                        .padding(start = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(Dates.time(slotTime), style = Type.Caption, color = if (now in slotTime until slotTime + 30 * 60_000L) Palette.TextPrimary else Palette.TextSecondary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GuideChannelCell(row: GuideRow, number: Int, onFocus: () -> Unit, onPlay: () -> Unit, onActions: () -> Unit) {
    FocusCard(
        onClick = onPlay,
        onLongClick = onActions,
        modifier = Modifier.width(ChannelColumn).fillMaxHeight(),
        shape = RoundedCornerShape(4.dp),
        focusedScale = 1f,
        background = Brush.verticalGradient(listOf(Palette.SurfaceElevated, Palette.SurfaceElevated)),
        restingBorder = Palette.HairlineStrong,
        onFocusChange = { if (it) onFocus() },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(number.toString(), style = Type.Digits, color = Palette.TextTertiary, modifier = Modifier.width(42.dp), maxLines = 1)
            ChannelLogo(row.pick.channel.logoUrl, 50.dp)
            Column(Modifier.weight(1f)) {
                Text(row.entry.name, style = Type.Callout, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.pick.stream.quality.label, style = Type.Caption, color = Palette.TextTertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ProgrammeRow(
    row: GuideRow,
    programmes: List<Programme>,
    windowStart: Long,
    windowEnd: Long,
    totalWidth: Dp,
    now: Long,
    onFocus: (GuideRow, Programme?) -> Unit,
    onPlay: (GuideRow) -> Unit,
) {
    val segments = remember(programmes, windowStart, windowEnd) { GuideTimelineLogic.segments(programmes, windowStart, windowEnd) }
    Row(Modifier.width(totalWidth).fillMaxHeight()) {
        segments.forEach { segment ->
            val width = PerMinute * ((segment.endMillis - segment.startMillis) / 60_000f)
            val programme = segment.programme
            val current = programme?.isOn(now) == true
            var focused by remember(segment.startMillis, segment.endMillis, programme) { mutableStateOf(false) }
            val interaction = remember(segment.startMillis, segment.endMillis, programme) { MutableInteractionSource() }
            val shape = RoundedCornerShape(4.dp)
            val background = when {
                current -> Brush.horizontalGradient(listOf(Palette.ActionFill.copy(alpha = 0.82f), Palette.Accent.copy(alpha = 0.48f)))
                programme == null -> Brush.verticalGradient(listOf(Palette.Background, Palette.Background))
                else -> Brush.verticalGradient(listOf(Palette.Surface, Palette.Surface))
            }
            // Dense EPG cells deliberately avoid FocusCard's graphics layer. A fourteen-hour "No information" span can
            // be thousands of pixels wide; drawing its ring directly avoids a large off-screen layer on low-RAM sticks.
            Box(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .padding(end = if (width > 4.dp) 2.dp else 0.dp)
                    .clip(shape)
                    .background(background)
                    .border(
                        width = if (focused) 3.dp else 1.dp,
                        color = if (focused) Color.White else if (current) Palette.AccessibleAccent.copy(alpha = 0.72f) else Palette.HairlineStrong,
                        shape = shape,
                    )
                    .onFocusChanged { state ->
                        if (focused != state.isFocused) {
                            focused = state.isFocused
                            if (state.isFocused) onFocus(row, programme)
                        }
                    }
                    .clickable(interactionSource = interaction, indication = null) { onPlay(row) },
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                    Text(
                        programme?.title ?: "No information",
                        style = Type.Callout,
                        color = when {
                            programme == null -> Palette.TextTertiary
                            programme.endMillis <= now -> Palette.TextTertiary
                            else -> Palette.TextPrimary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (width >= 150.dp && programme != null) {
                        Text("${Dates.time(programme.startMillis)} – ${Dates.time(programme.endMillis)}", style = Type.Caption, color = if (current) Color.White.copy(alpha = 0.76f) else Palette.TextTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun floorToHalfHour(millis: Long): Long = millis - millis % (30 * 60_000L)
