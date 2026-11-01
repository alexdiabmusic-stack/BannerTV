package com.alexdiab.stadiatv.ui.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.alexdiab.stadiatv.domain.Article
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.ui.common.AsyncContent
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.common.rememberAsync
import com.alexdiab.stadiatv.ui.components.ArticleCard
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.OverlayContentTop
import com.alexdiab.stadiatv.ui.components.OverlayScaffold
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.LocalTopInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NewsScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val followed = settings.followedLeagues
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val selected: League? = selectedPath?.let { League.find(it) }

    val loader = rememberAsync(selected?.path to followed.map { it.path }) {
        if (selected == null) container.news.latest(followed) else container.news.forLeague(selected)
    }

    Column(Modifier.fillMaxSize().padding(top = LocalTopInset.current - 20.dp)) {
        ChipRow {
            item(key = "all") { FilterChip("All", selected == null, { selectedPath = null }) }
            items(League.ALL, key = { it.path }) { league ->
                FilterChip(league.shortName, selected == league, { selectedPath = league.path })
            }
        }
        AsyncContent(loader, isEmpty = { it.isEmpty() }, emptyTitle = "No articles", emptySubtitle = "No news found for this selection.") { articles ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(400.dp),
                contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 24.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                items(articles, key = { it.id }) { article ->
                    ArticleCard(article, 400.dp, onClick = { navigator.push(Route.ArticleReader(article)) })
                }
            }
        }
    }
}

@Composable
fun ArticleScreen(article: Article) {
    val navigator = LocalNavigator.current
    OverlayScaffold(
        title = article.league?.name ?: article.source,
        subtitle = "News",
        scrollFade = true,
        trailing = { FocusPill(onClick = { navigator.pop() }, modifier = Modifier.initialFocus()) {
            Text("Done", style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = 36.dp).align(Alignment.Center))
        } },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = OverlayContentTop, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(Modifier.widthIn(max = 1180.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    if (!article.imageUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = article.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                                .clip(RoundedCornerShape(Shapes.Lg))
                                .background(Palette.SurfaceHigh),
                        )
                    }
                    Text(article.headline, style = Type.Display, color = Palette.TextPrimary)
                    if (article.description.isNotBlank()) {
                        Text(article.description, style = Type.Headline.copy(lineHeight = 34.sp), color = Palette.TextSecondary)
                    }
                    val meta = listOfNotNull(
                        article.byline?.takeIf { it.isNotBlank() },
                        article.source,
                        Dates.relative(article.publishedMillis).takeIf { it.isNotEmpty() },
                    )
                    Text(meta.joinToString(" · "), style = Type.Callout, color = Palette.TextTertiary)
                    Text(
                        "For the full story, visit ${articleHost(article)} on your phone or computer.",
                        style = Type.Callout,
                        color = Palette.TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.SurfaceElevated, RoundedCornerShape(Shapes.Md))
                            .padding(20.dp),
                    )
                }
            }
        }
    }
}

/** The publisher's site name for the "read the full story" hint, from the article link itself. */
private fun articleHost(article: Article): String =
    article.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.removePrefix("www.")
        ?: "${article.source}'s site"
