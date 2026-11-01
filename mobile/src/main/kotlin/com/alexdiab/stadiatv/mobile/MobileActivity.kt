package com.alexdiab.stadiatv.mobile

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MobileActivity : FragmentActivity() {
    private val model: MobileViewModel by viewModels()
    private var resumeAfterBackground = false
    var inPictureInPicture by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Replaying the launch Intent after a font-scale/theme recreation unwinds the user's current page,
        // or can restart a voice-requested podcast. The retained ViewModel already owns that navigation.
        if (savedInstanceState == null) {
            model.openLink(intent?.data)
            handleMediaSearch(intent)
        }
        setContent { PhoneWindowLayout(this) { PhoneApp(model, this) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.openLink(intent.data)
        handleMediaSearch(intent)
    }

    private fun handleMediaSearch(intent: android.content.Intent?) {
        if (intent?.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) model.playAudioSearch(intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty())
    }

    fun enterPlayerPip() {
        val podcastVideo = PhoneAudioService.ui.value.episode?.mime?.startsWith("video") == true
        if ((model.channel == null && !podcastVideo) || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        if (model.channel != null) model.fullPlayer = true else model.open(PhonePage.AudioPlayer)
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
    }

    @Suppress("DEPRECATION")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (model.channel != null && model.controller.player.isPlaying || PhoneAudioService.ui.value.let { it.playing && it.episode?.mime?.startsWith("video") == true }) enterPlayerPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            resumeAfterBackground = model.controller.player.playWhenReady
            model.controller.player.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            resumeAfterBackground = model.controller.player.playWhenReady
            model.controller.player.pause()
        }
    }

    override fun onStart() {
        super.onStart()
        if (resumeAfterBackground && model.channel != null) model.controller.player.play()
        resumeAfterBackground = false
    }

    override fun onResume() { super.onResume(); model.entitlements.refresh() }
}
