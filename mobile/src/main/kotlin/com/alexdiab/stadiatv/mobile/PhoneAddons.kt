package com.alexdiab.stadiatv.mobile

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.alexdiab.stadiatv.addons.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
fun PhoneAddons(model: MobileViewModel) {
    val library = model.container.addons
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var addons by remember { mutableStateOf(emptyList<AddonInfo>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<AddonInfo?>(null) }
    var key by remember { mutableStateOf<String?>(null) }
    var manifest by remember { mutableStateOf<Manifest?>(null) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    var titles by remember { mutableStateOf(emptyList<Title>()) }
    var detail by remember { mutableStateOf<Title?>(null) }
    var sources by remember { mutableStateOf<List<Stream>?>(null) }
    var captions by remember { mutableStateOf(emptyList<Subtitle>()) }
    var playing by remember { mutableStateOf<Stream?>(null) }
    val params = remember(catalog) { mutableStateMapOf<String,String>() }
    var lookup by remember(key) { mutableStateOf("") }
    var type by remember(key) { mutableStateOf("movie") }
    fun load(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: AddonProblem) { message = e.message }
            catch (_: Exception) { message = "Could not complete the request. Try again." }
            finally { busy = false }
        }
    }
    suspend fun refresh() { addons = withContext(Dispatchers.IO) { library.list() } }
    LaunchedEffect(library) { try { refresh() } catch (_: AddonProblem) { message = "The add-on vault is unavailable." } }
    suspend fun connect(): Connection = withContext(Dispatchers.IO) { library.connection(requireNotNull(key)) }
    suspend fun findStreams(t: String, id: String) {
        val selected = connect()
        // Stream-only add-ons supplement catalogs from another add-on, in installation order.
        val candidates = addons.filter { it.enabled }.sortedBy { it.key != key }
        val results = mutableListOf<Stream>(); val subtitles = mutableListOf<Subtitle>(); var unavailable = false
        for (provider in candidates) {
            val source = if (provider.key == key) selected else withContext(Dispatchers.IO) { library.connection(provider.key) }
            try { if (source.manifest.supports("stream",t,id)) results += library.client.streams(source.address,source.manifest,t,id) }
            catch (_: AddonProblem) { unavailable = true }
            try { if (source.manifest.supports("subtitles",t,id)) subtitles += library.client.subtitles(source.address,source.manifest,t,id) }
            catch (_: AddonProblem) { unavailable = true }
        }
        sources = results; captions = subtitles.distinctBy { it.url }
        if (unavailable) message = "One or more sources could not be refreshed."
    }
    fun back() {
        message = null
        when { playing != null -> playing = null; sources != null -> sources = null; detail != null -> detail = null
            catalog != null -> { catalog = null; titles = emptyList() }; else -> { key = null; manifest = null } }
    }
    BackHandler(key != null && editor == null && removal == null) { if (!busy) back() }
    if (editor != null) {
        var input by remember(editor) { mutableStateOf("") } // Never SavedStateHandle / rememberSaveable.
        AlertDialog(onDismissRequest = { if (!busy) { editor = null; message = null } },
            title = { Text(if (editor == "") "Connect an add-on" else "Replace connection") },
            text = { Column { Text("Paste the configured HTTPS manifest from AIOStreams or another compatible add-on.")
                OutlinedTextField(input,{ input = it }, singleLine=true, label={ Text("Manifest URL") },
                    visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
                message?.let { Text(it) }
            } }, confirmButton = { TextButton(enabled=!busy && input.isNotBlank(), onClick={
                load { val info = withContext(Dispatchers.IO) { library.connect(input,editor?.takeIf(String::isNotBlank)) }
                    input = ""; editor = null; refresh(); message = "Connected to ${info.name}." }
            }) { Text(if (busy) "Checking…" else "Connect") } }, dismissButton={ TextButton(enabled=!busy,onClick={ editor=null; message=null }) { Text("Cancel") } })
    }
    removal?.let { target -> AlertDialog(onDismissRequest={ removal=null },title={Text("Remove ${target.name}?")},
        text={Text("Its connection is removed from this device.")},confirmButton={ TextButton(onClick={
            removal=null; load { withContext(Dispatchers.IO) { library.remove(target.key) }; refresh() }
        }) { Text("Remove") } },dismissButton={TextButton(onClick={ removal=null }) { Text("Cancel") }}) }
    ScreenList {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (editor == null) message?.let { item { Text(it,style=MaterialTheme.typography.bodyMedium) } }
        if (key == null) {
            item { Text("Your sources",style=MaterialTheme.typography.headlineLarge) }
            item { Text("Connect a configured add-on to browse movies and shows. You can use an existing instance.") }
            item { Button(onClick={ editor=""; message=null },enabled=!busy) { Text("Connect an add-on") } }
            items(addons,key={ it.key }) { addon -> PhoneCard {
                Text(addon.name,style=MaterialTheme.typography.titleMedium)
                Text(if (addon.enabled) "Connected" else "Disabled")
                Row { TextButton(enabled=addon.enabled && !busy,onClick={ load {
                    val connection = withContext(Dispatchers.IO) { library.connection(addon.key) }
                    key=addon.key; manifest=connection.manifest
                } }) { Text("Browse") }
                    TextButton(enabled=!busy,onClick={ load { withContext(Dispatchers.IO) { library.check(addon.key) }; refresh(); message="Connection verified." } }) { Text("Check") }
                    TextButton(enabled=!busy,onClick={ editor=addon.key; message=null }) { Text("Edit") } }
                Row { TextButton(enabled=!busy,onClick={ load {
                    val url = withContext(Dispatchers.IO) { library.configurationPage(addon.key) }
                    context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } }) { Text("Configure") }
                    TextButton(enabled=!busy,onClick={ load { withContext(Dispatchers.IO) { library.enable(addon.key,!addon.enabled) }; refresh() } }) { Text(if (addon.enabled) "Disable" else "Enable") }
                    TextButton(enabled=!busy,onClick={ removal=addon }) { Text("Remove") } }
            } }
        } else if (playing != null) {
            item { AddonPlayer(playing!!,captions) }
        } else if (sources != null) {
            item { Text("Watch",style=MaterialTheme.typography.headlineLarge) }
            if (sources!!.isEmpty()) item { Text("No playable HTTPS streams were returned for this title.") }
            items(sources!!) { stream -> Button(enabled=!busy,onClick={playing=stream}) { Text(stream.name) } }
        } else if (detail != null) {
            val d = detail!!
            item { Text(d.name,style=MaterialTheme.typography.headlineLarge); Text(d.description) }
            if (d.type != "series") item { Button(enabled=!busy,onClick={load { findStreams(d.type,d.id) }}) { Text("Find streams") } }
            else if (d.videos.isEmpty()) item { Text("No episodes were supplied.") }
            items(d.videos) { v -> TextButton(enabled=!busy,onClick={load { findStreams(d.type,v.id) }}) { Text(listOfNotNull(v.season?.let { "S$it" },v.episode?.let { "E$it" },v.name).joinToString(" · ")) } }
        } else if (catalog != null) {
            val c=catalog!!
            item { Text(c.name,style=MaterialTheme.typography.headlineLarge) }
            items(c.extras.sorted()) { name -> OutlinedTextField(params[name].orEmpty(),{params[name]=it},label={Text(name + if (name in c.required) " (required)" else "")},singleLine=true) }
            item { Button(enabled=!busy && c.required.all { !params[it].isNullOrBlank() },onClick={load {
                val p=connect(); titles=library.client.catalog(p.address,p.manifest,c.type,c.id,params.filterValues(String::isNotBlank))
                if (titles.isEmpty()) message="No titles matched."
            }}) { Text("Browse titles") } }
            items(titles) { t -> TextButton(enabled=!busy,onClick={load {
                val p=connect(); detail=if (p.manifest.supports("meta",t.type,t.id)) library.client.metadata(p.address,p.manifest,t.type,t.id) else t
            }}) { Text(t.name) } }
        } else {
            item { Text(manifest?.name.orEmpty(),style=MaterialTheme.typography.headlineLarge) }
            items(manifest?.catalogs.orEmpty()) { c -> TextButton(enabled=!busy,onClick={catalog=c; titles=emptyList()}) { Text(c.name) } }
            item { Text("Look up a source by video ID"); ChoiceRow(listOf("movie","series"),type) { type=it }
                OutlinedTextField(lookup,{lookup=it},singleLine=true,label={Text(if(type=="movie") "Movie ID (tt…)" else "Episode ID (tt…:season:episode)")})
                TextButton(enabled=!busy && lookup.isNotBlank(),onClick={load { findStreams(type,lookup.trim()) }}) { Text("Find streams") } }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun AddonPlayer(stream: Stream, captions: List<Subtitle>, resumeMillis: Long = 0,
    onProgress: (Long,Long) -> Unit = { _,_ -> }) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(stream) {
        val origin = stream.url.toHttpUrl()
        val client = OkHttpClient.Builder().followSslRedirects(false).addNetworkInterceptor { chain ->
            val req=chain.request(); val url=req.url
            if (!url.isHttps) throw java.io.IOException("Secure media connection required")
            val next=req.newBuilder()
            for ((name,value) in stream.headers) {
                if (url.host == origin.host && url.port == origin.port && captions.none { it.url == url.toString() }) next.header(name,value)
                else next.removeHeader(name)
            }
            chain.proceed(next.build())
        }.build()
        val subtitles=captions.mapNotNull { s ->
            val mime=when (Uri.parse(s.url).lastPathSegment?.substringAfterLast('.')?.lowercase()) { "srt" -> MimeTypes.APPLICATION_SUBRIP; "vtt" -> MimeTypes.TEXT_VTT; "ttml" -> MimeTypes.APPLICATION_TTML; else -> return@mapNotNull null }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url)).setMimeType(mime).setLanguage(s.language).build()
        }
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT,true)
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.Builder().setUri(stream.url).setSubtitleConfigurations(subtitles).build())
            if (resumeMillis > 0) seekTo(resumeMillis)
            prepare(); playWhenReady=true
        }
    }
    var failed by remember(player) { mutableStateOf(false) }
    val progressCallback by rememberUpdatedState(onProgress)
    fun saveProgress() { if (player.duration > 0 && player.duration != C.TIME_UNSET) progressCallback(player.currentPosition.coerceAtLeast(0),player.duration) }
    LaunchedEffect(player) { while (isActive) { delay(5_000); saveProgress() } }
    DisposableEffect(player,owner) {
        val observer=LifecycleEventObserver { _,event -> if (event == Lifecycle.Event.ON_STOP) { saveProgress(); player.pause() } }
        val listener=object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failed=true } }
        owner.lifecycle.addObserver(observer); player.addListener(listener)
        onDispose { saveProgress(); owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    AndroidView(factory={ PlayerView(it).apply { this.player=player } },modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f))
    if (failed) TextButton(onClick={failed=false;player.prepare();player.play()}) { Text("Retry stream") }
}
