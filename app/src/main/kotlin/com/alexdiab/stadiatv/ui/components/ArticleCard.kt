package com.alexdiab.stadiatv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.alexdiab.stadiatv.domain.Article
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.util.Dates
import com.alexdiab.stadiatv.ui.theme.Type

/** News card: 16:9 image, league and source overline, headline and age. */
@Composable
fun ArticleCard(article: Article, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier.width(width)) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Palette.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Newspaper, null, tint = Palette.TextTertiary, modifier = Modifier.size(44.dp))
                if (!article.imageUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = article.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Soft fade so the card text below always reads against the photo's edge.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33000000)))),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (article.league?.shortName ?: article.source).uppercase(),
                        style = Type.Overline,
                        color = Palette.AccessibleAccent,
                        maxLines = 1,
                    )
                    // ESPN is the default voice; other publishers are named so the source is never hidden.
                    val meta = listOfNotNull(
                        article.source.takeIf { it != "ESPN" },
                        Dates.relative(article.publishedMillis).takeIf { it.isNotEmpty() },
                    ).joinToString("  ·  ")
                    if (meta.isNotEmpty()) Text(meta, style = Type.Caption, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    article.headline,
                    style = Type.BodyStrong,
                    color = Palette.TextPrimary,
                    // Two fixed lines keep every card in a row the same height.
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
