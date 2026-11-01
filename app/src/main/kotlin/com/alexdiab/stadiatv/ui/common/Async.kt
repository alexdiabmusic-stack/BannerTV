package com.alexdiab.stadiatv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay

/** Result of a one-shot load, shaped for direct use in a `when`. */
sealed interface Async<out T> {
    data object Loading : Async<Nothing>

    @Immutable
    data class Success<T>(val value: T) : Async<T>

    @Immutable
    data class Failure(val message: String, val unsupported: Boolean = false) : Async<Nothing>
}

class AsyncLoader<T>(val state: State<Async<T>>, val reload: () -> Unit)

/**
 * Loads [loader] whenever [key] changes (or [AsyncLoader.reload] is called) and exposes the result as
 * state. The previous value stays visible during a reload so the screen never flashes back to a spinner.
 */
@Composable
fun <T> rememberAsync(key: Any?, loader: suspend () -> T): AsyncLoader<T> {
    var attempt by remember { mutableStateOf(0) }
    val state = remember(key) { mutableStateOf<Async<T>>(Async.Loading) }
    LaunchedEffect(key, attempt) {
        val previous = state.value
        if (previous !is Async.Success) state.value = Async.Loading
        try {
            state.value = Async.Success(loader())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: UnsupportedOperationException) {
            state.value = Async.Failure(error.message ?: "Not available for this league.", unsupported = true)
        } catch (error: Throwable) {
            if (previous !is Async.Success) state.value = Async.Failure(error.message?.takeIf { it.isNotBlank() } ?: "Couldn't load this right now.")
        }
    }
    return remember(state) { AsyncLoader(state) { attempt++ } }
}

/** Takes focus when first shown. Used by pushed screens so the remote works immediately. */
@Composable
fun Modifier.initialFocus(enabled: Boolean = true): Modifier {
    val requester = remember { FocusRequester() }
    if (enabled) {
        LaunchedEffect(Unit) {
            awaitFrame()
            if (!runCatching { requester.requestFocus() }.getOrDefault(false)) {
                delay(120)
                runCatching { requester.requestFocus() }
            }
        }
    }
    return this.focusRequester(requester)
}
