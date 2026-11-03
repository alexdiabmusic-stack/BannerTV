package com.alexdiab.stadiatv.ui.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.alexdiab.stadiatv.AppContainer
import com.alexdiab.stadiatv.catalog.*
import com.alexdiab.stadiatv.addons.Stream
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import android.util.AtomicFile
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val bg = Color(0xFF090C12)
private val surface = Color(0xFF1B2029)
private val muted = Color(0xFFADB3BE)

/**
 * Fire TV / Android TV feature surface, independent from the phone's Cinema UI.
 * Reuses the SAME KMP repositories already in AppContainer; no new service or token.
 * Requires adding a tab/route and a metadata provisioning affordance in Settings.
 */
@Composable
fun BannerCinemaTV(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedStore = remember { TvCinemaLibrary(context.filesDir) }
    val initialLibrary = remember { runCatching { savedStore.load() } }
    var library by remember { mutableStateOf(initialLibrary.getOrDefault(MediaLibrary())) }
    var home by remember { mutableStateOf<CinemaHome?>(null) }
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var episodes by remember { mutableStateOf(emptyList<MediaEpisode>()) }
    var season by remember { mutableIntStateOf(-1) }
    var requestedId by remember { mutableStateOf<MediaId?>(null) }
    var selectedVideo by remember { mutableStateOf<MediaVideo?>(null) }
    var sources by remember { mutableStateOf<MediaSources?>(null) }
    var playing by remember { mutableStateOf<Stream?>(null) }
    var failure by remember { mutableStateOf<String?>(if (initialLibrary.isFailure) "Saved cinema library could not be read. Editing is disabled to protect it." else null) }
    var metadataToken by remember { mutableStateOf("") }
    var addonURL by remember { mutableStateOf("") }
    var openAddons by remember { mutableStateOf(false) }
    var showSavedOnly by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    val lang = remember { Locale.getDefault().toLanguageTag() }
    val artwork = home?.artwork

    fun save(value: MediaLibrary) {
        if (initialLibrary.isFailure) {
            failure = "Saved cinema library could not be read. Editing is disabled to protect it."
            return
        }
        try { savedStore.save(value); library = value }
        catch (_: Exception) { failure = "Changes could not be saved on this device." }
    }
    fun refresh() {
        scope.launch {
            failure = null
            try { home = container.tmdb.home(lang) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failure = "Cinema discovery is unavailable. Configure TMDB metadata or try again." }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(requestedId) {
        detail = null; episodes = emptyList(); season = -1
        val id = requestedId ?: return@LaunchedEffect
        try { detail = container.tmdb.detail(id, lang) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failure = "Could not load this title." }
    }
    LaunchedEffect(detail) {
        val d = detail ?: return@LaunchedEffect
        if (d.card.id.kind == MediaKind.Series) season = d.seasons.firstOrNull { it > 0 } ?: d.seasons.firstOrNull() ?: -1
    }
    LaunchedEffect(detail, season) {
        episodes = emptyList()
        val d = detail ?: return@LaunchedEffect
        if (d.card.id.kind != MediaKind.Series || season < 0) return@LaunchedEffect
        try { episodes = container.tmdb.episodes(d.card.id, season, lang) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failure = "Could not load episodes." }
    }
    LaunchedEffect(selectedVideo) {
        sources = null
        val video = selectedVideo ?: return@LaunchedEffect
        val d = detail ?: return@LaunchedEffect
        try { sources = container.mediaSources.resolve(d, video) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failure = "No streaming sources could be loaded." }
    }
    BackHandler(playing != null || selectedVideo != null || requestedId != null || showSetup || openAddons || showSavedOnly) {
        when {
            playing != null -> playing = null
            selectedVideo != null -> selectedVideo = null
            requestedId != null -> requestedId = null
            openAddons -> openAddons = false
            showSetup -> showSetup = false
            showSavedOnly -> showSavedOnly = false
        }
    }
    Column(Modifier.fillMaxSize().background(bg).padding(horizontal = 54.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            TvText("Movies & Shows", 30, Color.White, true)
            Spacer(Modifier.weight(1f))
            TvAction("Discover") { showSavedOnly=false; playing=null; selectedVideo=null; requestedId=null }
            TvAction("My list (${library.watchlist.size})") { showSavedOnly=true; requestedId=null }
            TvAction("Add-ons") { openAddons = !openAddons; showSetup=false }
            TvAction("Metadata") { showSetup = !showSetup; openAddons=false }
            TvAction("Refresh") { refresh() }
        }
        Spacer(Modifier.height(20.dp))
        when {
            playing != null && detail != null && selectedVideo != null -> {
                val stream = playing!!
                TvText(detail!!.card.title, 24, Color.White, true)
                TvCinemaPlayer(stream, library.progress.firstOrNull { it.video == selectedVideo }?.positionMillis ?: 0L) { position, duration ->
                    save(library.record(MediaProgress(detail!!.card, selectedVideo!!, position, duration, System.currentTimeMillis())))
                }
                TvAction("Change source") { playing=null }
            }
            showSetup -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TvText("Development metadata connection", 24, Color.White, true)
                TvText("Provide a TMDB read token at runtime. Never compile it into the APK.", 18, muted)
                BasicTextField(metadataToken, { metadataToken=it.take(4096) },
                    Modifier.width(620.dp).background(surface, RoundedCornerShape(8.dp)).padding(16.dp),
                    singleLine=true, textStyle = androidx.compose.ui.text.TextStyle(color=Color.White, fontSize=18.sp),
                    visualTransformation=PasswordVisualTransformation())
                TvAction("Save and refresh") {
                    scope.launch { try { container.metadataConnection.connect(metadataToken); metadataToken=""; showSetup=false; refresh() }
                        catch (_: Exception) { failure="Metadata token could not be validated." } }
                }
            }
            openAddons -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TvText("Connect an existing configured HTTPS /manifest.json URL", 23, Color.White)
                BasicTextField(addonURL, { addonURL=it.take(16384) },
                    Modifier.width(760.dp).background(surface, RoundedCornerShape(8.dp)).padding(16.dp),
                    singleLine=true, textStyle = androidx.compose.ui.text.TextStyle(color=Color.White, fontSize=18.sp),
                    visualTransformation=PasswordVisualTransformation())
                TvAction("Connect") { scope.launch {
                    try { container.addons.connect(addonURL); addonURL=""; openAddons=false }
                    catch (_: Exception) { failure="This add-on could not be connected." }
                } }
            }
            selectedVideo != null -> {
                TvText("Choose a source", 26, Color.White, true)
                val found = sources
                if (found == null) TvText("Checking your enabled add-ons…", 18, muted)
                else if (found.sources.isEmpty()) TvText("No compatible streams returned. Configure an add-on, or try another title.", 18, muted)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(found.sources, key = { it.provider + it.stream.url }) { source ->
                        TvAction("${source.provider} · ${source.stream.name}") { playing=source.stream }
                    }
                }
            }
            requestedId != null -> {
                val d=detail
                if (d == null) TvText("Loading title…", 20, muted)
                else TvDetail(d,artwork,library,d.card.id.kind == MediaKind.Series,season,episodes,
                    onSeason={ season=it },onPlay={ e ->
                        selectedVideo = if (e==null) MediaVideo(d.card.id)
                        else MediaVideo(d.card.id,e.season,e.number)
                    },onSaved={ save(library.toggle(d.card)) })
            }
            showSavedOnly -> {
                TvText("My list", 28, Color.White, true)
                if (library.watchlist.isEmpty()) TvText("Your saved movies and series will appear here.", 18, muted)
                else LazyColumn { item { TvRail(library.watchlist, artwork) { requestedId=it.id } } }
            }
            else -> {
                val h=home
                if (h == null) TvText(failure ?: "Loading your cinema library…", 20, muted)
                else LazyColumn(verticalArrangement=Arrangement.spacedBy(26.dp), contentPadding=PaddingValues(bottom=45.dp)) {
                    if (h.featured.isNotEmpty()) item {
                        val card = h.featured.first()
                        Box(Modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(18.dp))) {
                            AsyncImage(artwork?.url(card.backdropPath, true, 1280), null, Modifier.fillMaxSize(), contentScale=ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(bg,bg.copy(alpha=.6f),Color.Transparent))))
                            Column(Modifier.align(Alignment.CenterStart).padding(32.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                TvText(card.title, 36, Color.White,true)
                                TvText(listOfNotNull(card.releaseDate?.take(4), card.rating?.let { "TMDB %.1f".format(it) }).joinToString("  ·  "), 18, muted)
                                TvAction("View details") { requestedId=card.id }
                            }
                        }
                    }
                    if (library.continueWatching.isNotEmpty()) item {
                        TvText("Continue Watching", 23, Color.White, true)
                        TvRail(library.continueWatching.map { it.card },artwork) { requestedId=it.id }
                    }
                    if (library.watchlist.isNotEmpty()) item {
                        TvText("My list", 23, Color.White, true)
                        TvRail(library.watchlist,artwork) { requestedId=it.id }
                    }
                    h.shelves.forEach { shelf -> item(shelf.rail.name) {
                        TvText(shelf.rail.title, 23, Color.White, true)
                        TvRail(shelf.titles,artwork) { requestedId=it.id }
                    } }
                    item { TvText("This product uses the TMDB API but is not endorsed or certified by TMDB.",14,muted) }
                }
            }
        }
        if (failure != null && home != null) TvText(failure!!, 16, Color(0xFFFFC371))
    }
}

@Composable private fun TvDetail(d:MediaDetail, art:TmdbArtwork?, library:MediaLibrary, series:Boolean, season:Int,
                                 episodes:List<MediaEpisode>, onSeason:(Int)->Unit, onPlay:(MediaEpisode?)->Unit,
                                 onSaved:()->Unit) {
    val saved=library.watchlist.any { it.id==d.card.id }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                AsyncImage(art?.url(d.card.posterPath),null,Modifier.width(228.dp).height(340.dp).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Crop)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    TvText(d.card.title,36,Color.White,true)
                    TvText(listOfNotNull(d.yearLabel(),d.genres.take(3).joinToString(" · "),d.card.rating?.let { "TMDB %.1f".format(it) }).joinToString("   "),18,muted)
                    TvText(d.card.overview,20,Color.White)
                    Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        if (!series) TvAction("Play movie") { onPlay(null) }
                        TvAction(if(saved) "Remove from My list" else "Add to My list",onSaved)
                    }
                }
            }
        }
        if(series) {
            item { TvText("Episodes",26,Color.White,true) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                d.seasons.forEach { s -> TvAction(if(s==0) "Specials" else "Season $s${if(s==season) " ✓" else ""}") { onSeason(s) } }
            } }
            items(episodes,key={it.season.toString()+"/"+it.number}) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    AsyncImage(art?.url(e.stillPath,ArtworkKind.Still,300),null,Modifier.size(168.dp,96.dp),contentScale=ContentScale.Crop)
                    Column(Modifier.weight(1f)) {
                        TvText("${e.number}. ${e.title}",20,Color.White,true)
                        TvText(e.overview.take(240),15,muted)
                    }
                    TvAction("Play") { onPlay(e) }
                }
            }
        }
    }
}

@Composable private fun TvRail(cards:List<MediaCard>,art:TmdbArtwork?,open:(MediaCard)->Unit) {
    Spacer(Modifier.height(10.dp))
    LazyRow(horizontalArrangement=Arrangement.spacedBy(20.dp),contentPadding=PaddingValues(horizontal=4.dp,vertical=8.dp)) {
        items(cards,key={it.id.kind.path+":"+it.id.value}) { card ->
            var focused by remember { mutableStateOf(false) }
            val zoom by animateFloatAsState(if (focused) 1.065f else 1f,label="focus")
            Column(Modifier.width(150.dp).graphicsLayer { scaleX=zoom;scaleY=zoom }
                .onFocusChanged { focused=it.isFocused }.clip(RoundedCornerShape(10.dp))
                .border(if(focused) 2.dp else 0.dp,if(focused) Color.White else Color.Transparent,RoundedCornerShape(10.dp))
                .clickable { open(card) }.padding(3.dp)) {
                AsyncImage(art?.url(card.posterPath),card.title,Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Crop)
                TvText(card.title,15,Color.White,true)
            }
        }
    }
}
@Composable private fun TvText(value:String,size:Int,color:Color,bold:Boolean=false) {
    androidx.tv.material3.Text(value,color=color,fontSize=size.sp,fontWeight=if(bold) FontWeight.Bold else FontWeight.Normal,maxLines=if(size<20) 3 else Int.MAX_VALUE)
}
@Composable private fun TvAction(label:String,onClick:()->Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.onFocusChanged { focused=it.isFocused }
        .border(2.dp,if(focused) Color.White else Color.Transparent,RoundedCornerShape(9.dp))
        .background(if(focused) Color.White else surface,RoundedCornerShape(9.dp))
        .clickable(onClick=onClick).padding(horizontal=17.dp,vertical=12.dp)) {
        TvText(label,17,if(focused) bg else Color.White,true)
    }
}

/** A bounded, metadata-only, atomic local store. No add-on URLs or headers are written here. */
private class TvCinemaLibrary(directory:File) {
    private val file=AtomicFile(File(directory,"banner-cinema-tv-library.json"))
    private val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    fun load():MediaLibrary = try { json.decodeFromString<MediaLibrary>(file.openRead().bufferedReader().use { it.readText() }) }
        catch (e:java.io.FileNotFoundException) { MediaLibrary() }
        // A damaged library is an error: never present it as an empty library and overwrite it.
        catch (e:Exception) { throw e }
    fun save(value:MediaLibrary) {
        var stream:java.io.FileOutputStream?=null
        try { stream=file.startWrite(); stream.write(json.encodeToString(value).toByteArray(Charsets.UTF_8));file.finishWrite(stream) }
        catch(e:Exception) { stream?.let(file::failWrite); throw e }
    }
}

@Composable private fun TvCinemaPlayer(stream:Stream,startMillis:Long,onProgress:(Long,Long)->Unit) {
    val context=LocalContext.current
    val latestOnProgress by rememberUpdatedState(onProgress)
    val player=remember(stream.url, stream.headers) {
        val http=DefaultHttpDataSource.Factory().setDefaultRequestProperties(stream.headers)
        val source=DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context,http))
        ExoPlayer.Builder(context).setMediaSourceFactory(source).build().apply {
            setMediaItem(MediaItem.fromUri(stream.url));prepare();seekTo(startMillis);playWhenReady=true
        }
    }
    DisposableEffect(player) { onDispose {
        if(player.duration>0 && player.currentPosition>=0) latestOnProgress(player.currentPosition,player.duration)
        player.release()
    } }
    LaunchedEffect(player) { while(true) { delay(15_000);if(player.duration>0 && player.currentPosition>=0) latestOnProgress(player.currentPosition,player.duration) } }
    AndroidView(factory={ PlayerView(it).apply { this.player=player;useController=true } },
        modifier=Modifier.fillMaxWidth().height(510.dp))
}
