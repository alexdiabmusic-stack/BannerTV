package com.alexdiab.stadiatv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.Block
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.components.CenteredSpinner
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Type

/** Renders the loading, error, empty and loaded states of an [AsyncLoader] consistently. */
@Composable
fun <T> AsyncContent(
    loader: AsyncLoader<T>,
    modifier: Modifier = Modifier,
    isEmpty: (T) -> Boolean = { false },
    emptyTitle: String = "Nothing here yet",
    emptySubtitle: String? = null,
    content: @Composable (T) -> Unit,
) {
    when (val value = loader.state.value) {
        Async.Loading -> CenteredSpinner(modifier)
        is Async.Failure -> EmptyState(
            icon = if (value.unsupported) Icons.Rounded.Block else Icons.Rounded.WifiOff,
            title = if (value.unsupported) "Not available" else "Couldn't load this",
            subtitle = value.message,
            modifier = modifier,
            action = if (value.unsupported) null else ({ RetryPill(loader.reload) }),
        )
        is Async.Success -> if (isEmpty(value.value)) EmptyState(Icons.Rounded.Block, emptyTitle, modifier, emptySubtitle)
        else content(value.value)
    }
}

@Composable
fun RetryPill(onClick: () -> Unit) {
    FocusPill(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 28.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Refresh, null, tint = Palette.TextPrimary)
            Text("Try Again", style = Type.BodyStrong, color = Palette.TextPrimary)
        }
    }
}
