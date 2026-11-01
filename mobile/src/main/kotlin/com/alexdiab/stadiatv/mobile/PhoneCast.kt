package com.alexdiab.stadiatv.mobile

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.*
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.delay

/** Default Media Receiver requires no private receiver id or embedded service credentials. */
class PhoneCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

class PhoneCast(private val context: Context) {
    private var singleton: CastContext? = null
    fun available(): Boolean = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    fun castContext(): CastContext? {
        if (!available()) return null
        return singleton ?: runCatching { CastContext.getSharedInstance(context) }.getOrNull()?.also { singleton = it }
    }
    fun session(): CastSession? = castContext()?.sessionManager?.currentCastSession?.takeIf { it.isConnected }
    fun load(url: String, title: String, subtitle: String, image: String?, mime: String, live: Boolean, position: Long, done: (Boolean) -> Unit) {
        if (Uri.parse(url).scheme !in setOf("http", "https")) { done(false); return }
        val remote = session()?.remoteMediaClient ?: run { done(false); return }
        val metadata = MediaMetadata(if (mime.startsWith("audio/")) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title); putString(MediaMetadata.KEY_SUBTITLE, subtitle)
            image?.takeIf { Uri.parse(it).scheme == "https" }?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(url).setContentType(mime).setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(metadata).build()
        remote.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(if (live) 0 else position.coerceAtLeast(0)).build()).setResultCallback { result -> done(result.status.isSuccess) }
    }
}

@Composable
fun PhoneCastButton(model: MobileViewModel, onDarkVideo: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val supported = remember { model.cast.available() }
    if (!supported) { IconButton(onClick = { model.message = "Google Cast needs Google Play services and a compatible receiver on your network." }) { Icon(Icons.Rounded.Cast, "Casting availability") }; return }
    val dark = onDarkVideo || MaterialTheme.colorScheme.onSurface.luminance() > .5f
    val audio by PhoneAudioService.ui.collectAsStateWithLifecycle()
    val hasMedia by rememberUpdatedState(model.channel != null || audio.episode != null)
    DisposableEffect(model.cast) {
        val manager = model.cast.castContext()?.sessionManager
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) { if (hasMedia) model.castCurrent() }
            override fun onSessionStarting(session: CastSession) { }
            override fun onSessionStartFailed(session: CastSession, error: Int) { model.message = "The Cast connection couldn't start. Check the receiver and Wi-Fi network." }
            override fun onSessionEnding(session: CastSession) { }
            override fun onSessionEnded(session: CastSession, error: Int) { }
            override fun onSessionResuming(session: CastSession, sessionId: String) { }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { }
            override fun onSessionResumeFailed(session: CastSession, error: Int) { }
            override fun onSessionSuspended(session: CastSession, reason: Int) { }
        }
        manager?.addSessionManagerListener(listener, CastSession::class.java)
        onDispose { manager?.removeSessionManagerListener(listener, CastSession::class.java) }
    }
    key(dark) { AndroidView(factory = { MediaRouteButton(android.view.ContextThemeWrapper(it, if (dark) R.style.ThemeOverlay_BannerCastDark else R.style.ThemeOverlay_BannerCastLight)).apply { contentDescription = "Choose Google Cast device"; runCatching { CastButtonFactory.setUpMediaRouteButton(context, this) }.onFailure { isEnabled = false } } }, modifier = Modifier.size(48.dp)) }
}

@Composable
fun PhoneCastPanel(model: MobileViewModel) {
    val audio by PhoneAudioService.ui.collectAsStateWithLifecycle()
    var session by remember { mutableStateOf<CastSession?>(null) }
    var playing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    LaunchedEffect(model) {
        if (!model.cast.available()) return@LaunchedEffect
        while (true) { session = model.cast.session(); val remote = session?.remoteMediaClient; playing = remote?.isPlaying == true; title = remote?.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE).orEmpty(); delay(1000) }
    }
    val current = session ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Casting to ${current.castDevice?.friendlyName ?: "TV"}", style = MaterialTheme.typography.labelLarge)
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Row {
                TextButton(onClick = { val remote = current.remoteMediaClient; if (remote?.isPlaying == true) remote.pause() else remote?.play() }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null); Text(if (playing) "Pause" else "Play") }
                if (model.channel != null || audio.episode != null) TextButton(onClick = { model.castCurrent() }) { Text("Play current on TV") }
                TextButton(onClick = { model.cast.castContext()?.sessionManager?.endCurrentSession(true) }) { Text("Disconnect") }
            }
        }
    }
}
