package com.alexdiab.stadiatv.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.alexdiab.stadiatv.catalog.*
import com.alexdiab.stadiatv.addons.Stream
import kotlinx.coroutines.*
import java.util.Locale

private val CinemaBackground = Color(0xFF090C12)
private val CinemaSurface = Color(0xFF1B2029)
private val CinemaSecondary = Color(0xFFADB3BE)
private val PosterShape = RoundedCornerShape(14.dp)

@Composable
private fun CinemaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=darkColorScheme(background=CinemaBackground,surface=CinemaBackground,surfaceVariant=CinemaSurface,
        primary=Color.White,onPrimary=CinemaBackground,onBackground=Color.White,onSurface=Color.White,onSurfaceVariant=CinemaSecondary)) {
        Surface(Modifier.fillMaxSize(),color=CinemaBackground,content=content)
    }
}

@Composable
fun PhoneCinema(model: MobileViewModel) = CinemaTheme {
    val snapshots by model.mediaHome.state.collectAsStateWithLifecycle()
    val preferences by model.library.state.collectAsStateWithLifecycle()
    val connection by model.container.metadataConnection.state.collectAsStateWithLifecycle()
    val connected=connection==com.alexdiab.stadiatv.data.MetadataConnection.State.Ready
    var configure by remember { mutableStateOf(false) }
    val state = snapshots[model.mediaLanguage]
    val home = state?.value
    var library by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var rail by rememberSaveable { mutableStateOf<MediaRail?>(null) }
    var page by rememberSaveable(query,rail) { mutableIntStateOf(1) }
    var results by remember(query,rail) { mutableStateOf(emptyList<MediaCard>()) }
    var more by remember(query,rail) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    fun open(card: MediaCard) { model.open(PhonePage.CinemaDetail(card.id)) }
    LaunchedEffect(model.mediaLanguage,connected) { if (connected) model.mediaHome.refresh(model.mediaLanguage,true) }
    LaunchedEffect(query,rail,page,attempt,searching,connected) {
        if (!connected || rail == null && (!searching || query.isBlank())) { loading=false; return@LaunchedEffect }
        loading=true; failed=false
        try {
            if (rail == null) delay(350)
            val loaded = withContext(Dispatchers.IO) { rail?.let { model.container.tmdb.browse(it,page,model.mediaLanguage) }
                ?: model.container.tmdb.search(query,page,model.mediaLanguage) }
            results = (if (page == 1) loaded.results else results + loaded.results).distinctBy { it.id }
            more = loaded.page < loaded.pages
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed=true }
        finally { loading=false }
    }
    BackHandler(searching || rail != null || library) { searching=false; rail=null; library=false; query="" }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=!library,onClick={library=false;rail=null;searching=false},label={Text("Discover")})
            FilterChip(selected=library,onClick={library=true;rail=null;searching=false},label={Text("My list")})
            Spacer(Modifier.weight(1f))
            IconButton(onClick={searching=!searching;library=false;rail=null;query=""}) { Icon(Icons.Rounded.Search,"Search movies and series") }
            IconButton(onClick={model.open(PhonePage.Addons)}) { Icon(Icons.Rounded.Tune,"Manage streaming add-ons") }
        }
        if (searching) OutlinedTextField(query,{query=it.take(512)},Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),
            label={Text("Movies and series")},singleLine=true,trailingIcon={if(query.isNotEmpty()) IconButton(onClick={query=""}) { Icon(Icons.Rounded.Close,"Clear search") }})
        when {
            connection==com.alexdiab.stadiatv.data.MetadataConnection.State.Loading -> CinemaLoading()
            !connected -> Column {
                CinemaNotice(if(connection==com.alexdiab.stadiatv.data.MetadataConnection.State.Locked) "Metadata connection is locked" else "Discovery isn’t connected yet",
                    "Your connected add-on catalogs are available.","Browse add-ons") { model.open(PhonePage.Addons) }
                if(BuildConfig.DEBUG) TextButton(onClick={configure=true},Modifier.padding(horizontal=16.dp)) { Text("Set up metadata · Development") }
            }
            searching || rail != null -> Column {
                rail?.let { Text(it.title,Modifier.padding(16.dp),style=MaterialTheme.typography.headlineSmall) }
                if (searching && query.isBlank()) CinemaNotice("Find your next watch", "Search by a movie or series title.")
                else if (failed && results.isEmpty()) CinemaNotice("Couldn’t load titles", "Check your connection and try again.","Retry") { attempt++ }
                else if (!loading && !failed && results.isEmpty()) CinemaNotice("No titles found", "Try a different title or spelling.")
                else LazyVerticalGrid(columns=GridCells.Adaptive(130.dp),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                    items(results,key={"${it.id.kind}:${it.id.value}"}) { card -> CinemaPoster(card,home?.artwork,preferences.media.watchlist.any { it.id == card.id },Modifier.fillMaxWidth()) { open(card) } }
                    item(span={GridItemSpan(maxLineSpan)}) {
                        if (loading) CinemaLoading()
                        else if (failed) TextButton(onClick={attempt++}) { Text("Couldn’t load more · Retry") }
                        else if (more) OutlinedButton(onClick={page++},Modifier.fillMaxWidth()) { Text("Load more") }
                    }
                }
            }
            library -> if (preferences.media.watchlist.isEmpty()) CinemaNotice("Your next watches, together", "Save a movie or series from its detail page to find it here.")
                else LazyVerticalGrid(columns=GridCells.Adaptive(130.dp),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                    items(preferences.media.watchlist,key={"${it.id.kind}:${it.id.value}"}) { card -> CinemaPoster(card,home?.artwork,true,Modifier.fillMaxWidth()) { open(card) } }
                }
            home == null -> if (state?.failed == true) CinemaNotice("Movie discovery couldn’t load", "Check your connection and try again.","Retry") { model.mediaHome.refresh(model.mediaLanguage,true) } else CinemaLoading()
            else -> LazyColumn(verticalArrangement=Arrangement.spacedBy(28.dp),contentPadding=PaddingValues(bottom=32.dp)) {
                if (home.featured.isNotEmpty()) item("featured") { CinemaFeatured(home,model) }
                if (state?.failed == true) item("partial") { CinemaNotice("Some titles couldn’t refresh", "The available rows are shown below.","Retry") { model.mediaHome.refresh(model.mediaLanguage,true) } }
                if (preferences.media.continueWatching.isNotEmpty()) item("continue") {
                    CinemaHeading("Continue Watching")
                    LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        items(preferences.media.continueWatching,key={it.video.toString()}) { progress ->
                            Column(Modifier.width(264.dp).clip(PosterShape).clickable { open(progress.card) }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                                    CinemaImage(home.artwork.url(progress.card.backdropPath,true,780),Modifier.fillMaxSize())
                                    Text("${((progress.durationMillis-progress.positionMillis)/60_000).coerceAtLeast(1)}m left",Modifier.align(Alignment.TopEnd).padding(8.dp).background(CinemaBackground.copy(alpha=.9f),RoundedCornerShape(6.dp)).padding(6.dp),style=MaterialTheme.typography.labelMedium)
                                }
                                LinearProgressIndicator(progress={progress.fraction},modifier=Modifier.fillMaxWidth().semantics { contentDescription="Watch progress" },color=Color.White,trackColor=CinemaSurface)
                                Text(progress.card.title,Modifier.padding(top=8.dp),maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold)
                                progress.video.episode?.let { Text("S${progress.video.season} · E$it",color=CinemaSecondary,style=MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                }
                home.shelves.forEach { shelf -> item(shelf.rail.name) {
                    CinemaHeading(shelf.rail.title,onMore={rail=shelf.rail;page=1})
                    if (shelf.unavailable) CinemaNotice("This row couldn’t load", "Try refreshing discovery.","Retry") { model.mediaHome.refresh(model.mediaLanguage,true) }
                    else CinemaRow(shelf.titles,home.artwork,preferences.media,::open)
                } }
                item("credits") { CinemaCredits() }
            }
        }
    }
    if(configure) MetadataSetup(model,onDismiss={configure=false})
}

@Composable
private fun MetadataSetup(model: MobileViewModel, onDismiss: () -> Unit) {
    // Deliberately excluded from saved UI state, preferences backup, diagnostics and build artifacts.
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy) {token="";onDismiss()}},title={Text("Connect metadata")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Development setup: enter the TMDB API read access token. It is validated and encrypted on this device.")
            OutlinedTextField(token,{token=it.take(4096)},label={Text("Read access token")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
            if(error) Text("The connection could not be verified or saved. Check the token and try again.")
        }},confirmButton={TextButton(enabled=!busy && token.isNotBlank(),onClick={busy=true;error=false;scope.launch {
            try { model.container.metadataConnection.connect(token);token="";onDismiss() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { error=true }
            finally {busy=false}
        }}) {Text(if(busy) "Checking…" else "Connect")}},dismissButton={TextButton(enabled=!busy,onClick={token="";onDismiss()}) {Text("Cancel")}})
}

@Composable
private fun CinemaFeatured(home: CinemaHome, model: MobileViewModel) {
    val cards = home.featured
    val pager = rememberPagerState(pageCount={cards.size})
    val details by model.mediaDetails.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage,cards) { cards.getOrNull(pager.currentPage)?.let { model.mediaDetails.refresh(it.id) } }
    Column {
        HorizontalPager(state=pager,key={"${cards[it].id.kind}:${cards[it].id.value}"}) { index ->
            val card=cards[index]; val detail=details[card.id]?.value
            CinemaHero(card,detail,home.artwork) {
                Button(onClick={model.open(PhonePage.CinemaDetail(card.id))},shape=CircleShape,contentPadding=PaddingValues(horizontal=30.dp,vertical=14.dp)) { Text("View details",fontWeight=FontWeight.Bold) }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
            cards.forEachIndexed { index, _ ->
                // Visual dots have full 48dp touch targets and an explicit selected state.
                Box(Modifier.size(48.dp).selectable(selected=pager.currentPage==index,role=Role.Tab,onClick={scope.launch { pager.animateScrollToPage(index) }})
                    .semantics { contentDescription="Featured title ${index+1} of ${cards.size}" },contentAlignment=Alignment.Center) {
                    Box(Modifier.size(width=if (pager.currentPage==index) 26.dp else 7.dp,height=7.dp).background(if(pager.currentPage==index) Color.White else CinemaSecondary.copy(alpha=.5f),CircleShape))
                }
            }
        }
    }
}

@Composable
private fun CinemaHero(card: MediaCard, detail: MediaDetail?, art: TmdbArtwork?, action: @Composable ColumnScope.() -> Unit) {
    val height = (LocalConfiguration.current.screenHeightDp*.58f).coerceIn(330f,540f).dp
    Box(Modifier.fillMaxWidth().heightIn(min=height)) {
        CinemaImage(art?.url(detail?.heroPath ?: card.backdropPath,ArtworkKind.Backdrop,1280) ?: art?.url(card.posterPath),Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent,.35f to CinemaBackground.copy(alpha=.12f),1f to CinemaBackground)))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(top=height*.48f,start=24.dp,end=24.dp,bottom=16.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            val logo = art?.url(detail?.logoPath,ArtworkKind.Logo,500)
            var logoFailed by remember(logo) { mutableStateOf(false) }
            if (logo != null && !logoFailed) AsyncImage(logo,card.title,Modifier.widthIn(max=300.dp).fillMaxWidth().height(86.dp),contentScale=ContentScale.Fit,onError={logoFailed=true})
            else Text(card.title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            val meta = listOfNotNull(if(card.id.kind==MediaKind.Movie) "Movie" else "Series",detail?.genres?.take(2)?.joinToString(" · ")?.takeIf(String::isNotEmpty),detail?.yearLabel()?.takeIf(String::isNotEmpty) ?: card.releaseDate?.take(4))
            Text(meta.joinToString(" · "),color=Color(0xFFD6D9DF),style=MaterialTheme.typography.bodyMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            action()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneCinemaDetail(id: MediaId, model: MobileViewModel) = CinemaTheme {
    val details by model.mediaDetails.state.collectAsStateWithLifecycle()
    val artworks by model.mediaArtwork.state.collectAsStateWithLifecycle()
    val preferences by model.library.state.collectAsStateWithLifecycle()
    val state=details[id]; val detail=state?.value; val art=artworks["images"]?.value
    val scope=rememberCoroutineScope()
    var season by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    var episodes by remember(id,season) { mutableStateOf<List<MediaEpisode>?>(null) }
    var episodeError by remember(id,season) { mutableStateOf(false) }
    var episodeAttempt by remember { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf<MediaVideo?>(null) }
    var sources by remember { mutableStateOf<MediaSources?>(null) }
    var sourceError by remember { mutableStateOf(false) }
    var sourceAttempt by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<Stream?>(null) }
    var episodeTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { model.mediaDetails.refresh(id); model.mediaArtwork.refresh("images") }
    LaunchedEffect(detail) { if(season==null) season=detail?.seasons?.firstOrNull { it>0 } ?: detail?.seasons?.firstOrNull() }
    LaunchedEffect(id,season,episodeAttempt) {
        val s=season ?: return@LaunchedEffect
        episodes=null;episodeError=false
        try { episodes=withContext(Dispatchers.IO) { model.container.tmdb.episodes(id,s,model.mediaLanguage) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { episodeError=true }
    }
    LaunchedEffect(selection,sourceAttempt) {
        val video=selection ?: return@LaunchedEffect
        val d=detail ?: return@LaunchedEffect
        sources=null;sourceError=false
        try { sources=withContext(Dispatchers.IO) { model.container.mediaSources.resolve(d,video) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { sourceError=true }
    }
    BackHandler(playing!=null) { playing=null }
    if (playing!=null && selection!=null && detail!=null) {
        val video=selection!!
        val resume=preferences.media.progress.firstOrNull { it.video==video && it.resumable }?.positionMillis ?: 0L
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { IconButton(onClick={playing=null}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Choose another stream") }; Text(detail.card.title,style=MaterialTheme.typography.titleLarge) }
            AddonPlayer(playing!!,sources?.subtitles.orEmpty(),resume) { position,duration ->
                model.library.update { it.copy(media=it.media.record(MediaProgress(detail.card,video,position,duration,System.currentTimeMillis(),episodeTitle))) }
            }
            OutlinedButton(onClick={playing=null},Modifier.fillMaxWidth()) { Text("Choose another stream") }
        }
    } else if (detail==null) {
        Column {
            IconButton(onClick=model::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back") }
            if(state?.failed==true) CinemaNotice("This title couldn’t load", "Check your connection and try again.","Retry") { model.mediaDetails.refresh(id,true) } else CinemaLoading()
        }
    } else {
        val saved=preferences.media.watchlist.any { it.id==id }
        val resume=preferences.media.continueWatching.firstOrNull { it.card.id==id }
        val scroll=rememberLazyListState()
        LazyColumn(state=scroll,verticalArrangement=Arrangement.spacedBy(24.dp),contentPadding=PaddingValues(bottom=32.dp)) {
            item("hero") {
                Box {
                    CinemaHero(detail.card,detail,art) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Button(enabled=detail.imdbId!=null,onClick={
                                when { resume!=null -> { selection=resume.video;episodeTitle=resume.episodeTitle }
                                    id.kind==MediaKind.Movie -> { selection=MediaVideo(id);episodeTitle=null }
                                    else -> scope.launch { scroll.animateScrollToItem(3) } }
                            },modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=CircleShape) {
                                Icon(Icons.Rounded.PlayArrow,null); Spacer(Modifier.width(8.dp))
                                Text(if(resume!=null) "Resume" else if(id.kind==MediaKind.Series) "Episodes" else "Play",fontWeight=FontWeight.Bold)
                            }
                            FilledTonalIconButton(onClick={model.library.update { it.copy(media=it.media.toggle(detail.card)) }},modifier=Modifier.size(52.dp)) {
                                Icon(if(saved) Icons.Rounded.Check else Icons.Rounded.Add,if(saved) "Remove from My list" else "Add to My list")
                            }
                        }
                    }
                    FilledTonalIconButton(onClick=model::back,modifier=Modifier.align(Alignment.TopStart).padding(16.dp).size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back") }
                }
            }
            item("metadata") { Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(detail.yearLabel(),fontWeight=FontWeight.Bold)
                    detail.runtimeMinutes?.let { Text(if(id.kind==MediaKind.Series) "${runtimeText(it)} / episode" else runtimeText(it),fontWeight=FontWeight.SemiBold) }
                    detail.card.rating?.let { Text("TMDB ${String.format(Locale.ROOT,"%.1f",it)}",color=Color(0xFFE2C37B),fontWeight=FontWeight.Bold) }
                }
                if(detail.directors.isNotEmpty()) CinemaCreditLine("Director",detail.directors)
                if(detail.writers.isNotEmpty()) CinemaCreditLine("Writer",detail.writers)
                if(detail.creators.isNotEmpty()) CinemaCreditLine("Created by",detail.creators)
                Text(detail.card.overview.ifBlank { "A synopsis isn’t available yet." },style=MaterialTheme.typography.bodyLarge,lineHeight=26.sp)
                if(detail.imdbId==null) Text("This title has no verified playback identity yet.",color=CinemaSecondary)
                else Text("Playback uses your connected add-ons.",color=CinemaSecondary,style=MaterialTheme.typography.bodySmall)
                if(state?.failed==true) TextButton(onClick={model.mediaDetails.refresh(id,true)}) { Text("Showing saved details · Retry refresh") }
                if(artworks["images"]?.failed==true) TextButton(onClick={model.mediaArtwork.refresh("images",true)}) { Text("Retry artwork") }
            } }
            item("cast") { if(detail.cast.isNotEmpty()) {
                CinemaHeading("Cast")
                LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    items(detail.cast,key={it.id}) { person -> Column(Modifier.width(100.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        var missing by remember(person.profilePath) { mutableStateOf(false) }
                        val photo=art?.url(person.profilePath,ArtworkKind.Profile,185)
                        Box(Modifier.size(88.dp).clip(CircleShape).background(CinemaSurface),contentAlignment=Alignment.Center) {
                            Text(person.name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""),fontWeight=FontWeight.Bold,color=CinemaSecondary)
                            if(photo!=null && !missing) AsyncImage(photo,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop,onError={missing=true})
                        }
                        Text(person.name,Modifier.padding(top=8.dp),style=MaterialTheme.typography.labelLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(person.role,color=CinemaSecondary,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    } }
                }
            } }
            if(id.kind==MediaKind.Series) {
                item("seasons") {
                    CinemaHeading("Episodes")
                    LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(detail.seasons) { s ->
                        FilterChip(selected=season==s,onClick={season=s},label={Text(if(s==0) "Specials" else "Season $s")})
                    } }
                    if(detail.seasons.isEmpty()) CinemaNotice("No episodes listed", "Episode details haven’t been supplied yet.")
                    else if(episodeError) CinemaNotice("Episodes couldn’t load", "Try again to see this season.","Retry") { episodeAttempt++ }
                    else if(episodes==null) CinemaLoading()
                    else if(episodes!!.isEmpty()) CinemaNotice("No episodes in this season", "Check another season or return later.")
                }
                items(episodes.orEmpty(),key={"episode:${it.season}:${it.number}"}) { episode ->
                    Column(Modifier.padding(horizontal=16.dp).fillMaxWidth().clip(PosterShape).background(CinemaSurface).clickable(enabled=detail.imdbId!=null) {
                        selection=MediaVideo(id,episode.season,episode.number);episodeTitle=episode.title
                    }.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            CinemaImage(art?.url(episode.stillPath,ArtworkKind.Still,300),Modifier.width(128.dp).aspectRatio(16f/9f).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f)) {
                                Text("${episode.number}. ${episode.title}",fontWeight=FontWeight.SemiBold)
                                Text(listOfNotNull(episode.runtimeMinutes?.let(::runtimeText),episode.airDate?.takeIf(String::isNotBlank)).joinToString(" · "),color=CinemaSecondary,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        if(episode.overview.isNotBlank()) Text(episode.overview,maxLines=3,overflow=TextOverflow.Ellipsis,color=CinemaSecondary,style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if(detail.recommendations.isNotEmpty()) item("related") { CinemaHeading("More like this"); CinemaRow(detail.recommendations,art,preferences.media) { model.open(PhonePage.CinemaDetail(it.id)) } }
            item("credits") { CinemaCredits() }
        }
    }
    if(selection!=null && playing==null) ModalBottomSheet(onDismissRequest={selection=null},containerColor=CinemaSurface) {
        LazyColumn(contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("Choose a stream",style=MaterialTheme.typography.headlineSmall); Text(episodeTitle ?: detail?.card?.title.orEmpty(),color=CinemaSecondary) }
            when {
                sourceError -> item { CinemaNotice("Sources couldn’t load", "Check your add-ons and connection.","Retry") { sourceAttempt++ } }
                sources==null -> item { CinemaLoading() }
                else -> {
                    val result=sources!!
                    if(result.failedProviders>0) item { Text("${result.failedProviders} add-on(s) couldn’t respond. Other results are shown below.",color=CinemaSecondary); TextButton(onClick={sourceAttempt++}) { Text("Retry sources") } }
                    if(result.sources.isEmpty()) item { CinemaNotice(if(result.enabledProviders==0) "Connect a streaming source" else "No compatible streams returned",
                        "Use a configured AIOStreams or Stremio-compatible add-on.","Manage add-ons") { selection=null;model.open(PhonePage.Addons) } }
                    items(result.sources) { source -> OutlinedButton(onClick={model.stopPlayer();PhoneAudioService.pauseActive();playing=source.stream},Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical=6.dp)) { Text(source.stream.name,fontWeight=FontWeight.SemiBold); Text(source.provider,style=MaterialTheme.typography.labelMedium,color=CinemaSecondary) }
                    } }
                }
            }
        }
    }
}

@Composable
private fun CinemaImage(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(CinemaSurface),contentAlignment=Alignment.Center) {
        Icon(Icons.Rounded.Movie,null,tint=CinemaSecondary.copy(alpha=.3f),modifier=Modifier.size(32.dp))
        if(url!=null) AsyncImage(url,null,Modifier.matchParentSize(),contentScale=ContentScale.Crop)
    }
}

@Composable
private fun CinemaPoster(card: MediaCard, art: TmdbArtwork?, saved: Boolean, modifier: Modifier=Modifier, onClick: () -> Unit) {
    Column(modifier.clip(PosterShape).clickable(onClick=onClick).semantics(mergeDescendants=true) { role=Role.Button }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f/3f).clip(PosterShape)) {
            CinemaImage(art?.url(card.posterPath,desiredWidth=342),Modifier.fillMaxSize())
            if(saved) Icon(Icons.Rounded.Check,"In My list",Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.White,CircleShape).padding(4.dp).size(18.dp),tint=CinemaBackground)
        }
        Text(card.title,Modifier.padding(top=8.dp),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyLarge)
        Text(card.releaseDate?.take(4).orEmpty(),Modifier.padding(top=4.dp),color=CinemaSecondary,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CinemaRow(cards: List<MediaCard>, art: TmdbArtwork?, library: MediaLibrary, open: (MediaCard) -> Unit) {
    LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        items(cards,key={"${it.id.kind}:${it.id.value}"}) { card -> CinemaPoster(card,art,library.watchlist.any { it.id==card.id },Modifier.width(140.dp)) { open(card) } }
    }
}

@Composable
private fun CinemaHeading(title: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f).semantics { heading() },style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        if(onMore!=null) IconButton(onClick=onMore) { Icon(Icons.AutoMirrored.Rounded.ArrowForward,"See all $title") }
    }
}

@Composable
private fun CinemaCreditLine(label: String, names: List<String>) { Text("$label: ${names.joinToString(", ")}",color=CinemaSecondary,style=MaterialTheme.typography.bodyMedium) }

@Composable
private fun CinemaNotice(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium); Text(body,color=CinemaSecondary,style=MaterialTheme.typography.bodyMedium)
        if(action!=null) OutlinedButton(onClick=onAction) { Text(action) }
    }
}

@Composable
private fun CinemaLoading() { Box(Modifier.fillMaxWidth().padding(32.dp),contentAlignment=Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription="Loading titles" },strokeWidth=2.dp) } }

@Composable
private fun CinemaCredits() {
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Metadata & artwork",style=MaterialTheme.typography.labelLarge,color=CinemaSecondary)
        AsyncImage("file:///android_asset/tmdb-logo.svg","The Movie Database",Modifier.width(96.dp).height(16.dp),contentScale=ContentScale.Fit)
        Text(TmdbRepository.ATTRIBUTION,color=CinemaSecondary,style=MaterialTheme.typography.bodySmall)
    }
}

private fun runtimeText(minutes: Int): String = if(minutes>=60) "${minutes/60}h ${minutes%60}m" else "${minutes}m"
