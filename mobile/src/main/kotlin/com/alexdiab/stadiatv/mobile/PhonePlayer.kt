@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.alexdiab.stadiatv.mobile

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.playback.*
import kotlinx.coroutines.delay

@Composable
fun PlayerVideo(controller: PlaybackController, modifier: Modifier = Modifier, showNativeControls: Boolean = true, aspect: AspectMode = AspectMode.Fit) {
    val playback by controller.ui.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
    val frame = VideoSizing.original(playback.videoWidth, playback.videoHeight, playback.videoPixelRatio, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    val videoModifier = if (aspect == AspectMode.Original) with(density) { Modifier.size(frame.width.toDp(), frame.height.toDp()) } else Modifier.fillMaxSize()
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = showNativeControls
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setKeepContentOnPlayerReset(true)
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowFastForwardButton(false)
                setShowRewindButton(false)
                setShowSubtitleButton(true)
                player = controller.player
            }
        },
        update = {
            if (it.player !== controller.player) it.player = controller.player
            it.useController = showNativeControls
            it.resizeMode = when (aspect) { AspectMode.Fit, AspectMode.Original -> AspectRatioFrameLayout.RESIZE_MODE_FIT; AspectMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL; AspectMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM }
        },
        onRelease = { it.player = null }, modifier = videoModifier,
    )
    }
}

