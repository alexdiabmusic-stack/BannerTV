package com.alexdiab.stadiatv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.R
import com.alexdiab.stadiatv.data.Favourites
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.icon
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Tv
import com.alexdiab.stadiatv.ui.theme.Type

private enum class Step { Welcome, Sports, Leagues, Teams, Playlist, Done }

/** First-run setup: sports, leagues, favourite teams and (optionally) a playlist. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen() {
    val container = LocalContainer.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    // Redoing setup starts from what is already chosen, so it edits rather than starts blank.
    var sports by rememberSaveable { mutableStateOf(Favourites.sportsOf(settings.selectedLeagueIds).map { it.name }.toSet()) }
    var leagues by rememberSaveable { mutableStateOf(settings.selectedLeagueIds) }

    val selectedSports = SportGroup.entries.filter { it.name in sports }
    val canAdvance = when (step) {
        Step.Sports -> sports.isNotEmpty()
        Step.Leagues -> leagues.isNotEmpty()
        else -> true
    }

    fun back() { Step.entries.getOrNull(step.ordinal - 1)?.let { step = it } }
    fun advance() {
        // Narrowing the leagues also drops teams starred in leagues that were just switched off.
        if (step == Step.Leagues) container.preferences.setLeagues(leagues, pruneTeams = true)
        if (step == Step.Done) { container.preferences.completeOnboarding(); return }
        Step.entries.getOrNull(step.ordinal + 1)?.let { step = it }
    }
    BackHandler(enabled = step != Step.Welcome) { back() }

    Box(Modifier.fillMaxSize().background(Palette.Background).background(Tv.gradients.screen)) {
        Column(Modifier.fillMaxSize()) {
            if (step != Step.Welcome) {
                Row(Modifier.padding(horizontal = 96.dp).padding(top = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Step.entries.drop(1).forEach { s ->
                        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(if (s.ordinal <= step.ordinal) Palette.Accent else Palette.SurfaceElevated))
                    }
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (fadeIn(tween(240)) + slideInHorizontally(tween(300)) { if (forward) it / 12 else -it / 12 }) togetherWith fadeOut(tween(140))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "step",
            ) { current ->
                when (current) {
                    Step.Welcome -> Welcome()
                    // Choosing a sport chooses all of its leagues (narrow them on the next step); unchoosing it clears them.
                    Step.Sports -> SportsStep(sports) { name ->
                        val own = SportGroup.entries.firstOrNull { it.name == name }?.let(Favourites::leaguesOf).orEmpty()
                        if (name in sports) { sports = sports - name; leagues = leagues - own } else { sports = sports + name; leagues = leagues + own }
                    }
                    Step.Leagues -> LeaguesStep(selectedSports, leagues) { path -> leagues = if (path in leagues) leagues - path else leagues + path }
                    Step.Teams -> Column(Modifier.fillMaxSize()) {
                        StepHeader("Favourite teams", "Optional. Their games lead Home and My Banner, and are the first ones we find a stream for. Change this any time in Settings.")
                        TeamPickerContent(leagues = League.ALL.filter { it.path in leagues }, focusFirstTeam = true)
                    }
                    Step.Playlist -> PlaylistStep(onAdded = { step = Step.Done })
                    Step.Done -> Done()
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 96.dp, vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (step != Step.Welcome) FooterButton("Back", primary = false, enabled = true, modifier = Modifier.weight(1f), onClick = ::back)
                FooterButton(
                    when (step) { Step.Welcome -> "Get Started"; Step.Playlist -> "Skip for now"; Step.Done -> "Start Watching"; else -> "Continue" },
                    primary = true, enabled = canAdvance, modifier = Modifier.weight(1f).then(if (step == Step.Welcome || step == Step.Done) Modifier.initialFocus() else Modifier),
                    onClick = ::advance,
                )
            }
        }
    }
}

@Composable
private fun FooterButton(label: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(Shapes.Lg),
        enabled = enabled,
        focusedScale = 1.03f,
        background = if (primary) Brush.horizontalGradient(listOf(Palette.Accent.copy(alpha = if (enabled) 1f else 0.4f), Palette.ActionFill.copy(alpha = if (enabled) 1f else 0.4f)))
        else Brush.verticalGradient(listOf(Palette.Surface, Palette.Surface)),
    ) {
        Text(label, style = Type.Title3, color = if (primary) Color.White else Palette.TextSecondary, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 96.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = Type.Display, color = Palette.TextPrimary)
        Text(subtitle, style = Type.Headline, color = Palette.TextSecondary)
    }
}

@Composable
private fun Welcome() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.app_icon_raster), null, Modifier.size(150.dp))
        Text("BannerTV", style = Type.Display, color = Palette.TextPrimary, modifier = Modifier.padding(top = 28.dp))
        Text(
            "Live matches, scores and your playlists, all in one place.",
            style = Type.Title3.copy(fontWeight = FontWeight.Normal), color = Palette.TextSecondary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp).widthIn(max = 820.dp),
        )
    }
}

@Composable
private fun SportsStep(selected: Set<String>, onToggle: (String) -> Unit) {
    val groups = remember { League.groups }
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            "Pick your sports",
            if (selected.isEmpty()) "Choose at least one to continue. Home, Following and the featured game are built from your choices."
            else "${selected.size} chosen. Home, Following and the featured game are built from these. Change them any time in Settings.",
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(260.dp),
            contentPadding = PaddingValues(start = 96.dp, end = 96.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            items(groups, key = { it.name }) { sport ->
                val on = sport.name in selected
                FocusCard(
                    onClick = { onToggle(sport.name) },
                    modifier = Modifier.fillMaxWidth().height(170.dp).then(if (sport == groups.first()) Modifier.initialFocus() else Modifier),
                    focusedScale = 1.05f,
                    background = if (on) Brush.linearGradient(listOf(Palette.Accent, Palette.ActionFill)) else Brush.verticalGradient(listOf(Palette.Surface, Palette.Surface)),
                    restingBorder = if (on) Color.Transparent else Palette.Hairline,
                ) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(sport.icon, null, tint = if (on) Color.White else Palette.AccessibleAccent, modifier = Modifier.size(56.dp))
                        Text(sport.label, style = Type.Title3, color = if (on) Color.White else Palette.TextPrimary, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeaguesStep(sports: List<SportGroup>, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            "Choose leagues",
            if (selected.isEmpty()) "Choose at least one league to continue."
            else "${selected.size} chosen. Only these appear on Home and in your Schedule; every game is still in the Sports tab.",
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 96.dp, end = 96.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            sports.forEachIndexed { index, sport ->
                item(key = sport.name) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(sport.icon, null, tint = Palette.AccessibleAccent, modifier = Modifier.size(30.dp))
                            Text(sport.label, style = Type.Title3, color = Palette.TextPrimary)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            League.inGroup(sport).forEachIndexed { i, league ->
                                FilterChip(
                                    league.name, league.path in selected, { onToggle(league.path) },
                                    modifier = if (index == 0 && i == 0) Modifier.initialFocus() else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistStep(onAdded: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        StepHeader("Add your playlist", "Optional. Add an M3U link or stream login to watch matches in the app. You can do this later in Settings.")
        LazyColumn(contentPadding = PaddingValues(start = 96.dp, end = 96.dp, bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
            item { PlaylistForm(editing = null, onSaved = onAdded, modifier = Modifier.widthIn(max = 1100.dp)) }
        }
    }
}

@Composable
private fun Done() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.CheckCircle, null, tint = Palette.Accent, modifier = Modifier.size(110.dp))
        Text("You're all set!", style = Type.Display, color = Palette.TextPrimary, modifier = Modifier.padding(top = 24.dp))
        Text("Your games are loading now.", style = Type.Title3.copy(fontWeight = FontWeight.Normal), color = Palette.TextSecondary, modifier = Modifier.padding(top = 10.dp))
        Text(
            "Change your sports, leagues and teams any time in Settings, under Favourites.",
            style = Type.Callout, color = Palette.TextTertiary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp).widthIn(max = 820.dp),
        )
    }
}
