@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.alexdiab.stadiatv.mobile

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.Article
import com.alexdiab.stadiatv.net.HttpGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.File
import java.security.MessageDigest

@Serializable
data class ReadableArticle(val id: String, val title: String, val url: String, val paragraphs: List<String>, val fetchedAt: Long, val restricted: Boolean = false)

class PhoneArticleStore(context: Context, private val http: HttpGateway) {
    private val directory = File(context.filesDir, "offline-articles")
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(id: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    fun cached(id: String): ReadableArticle? = runCatching { json.decodeFromString<ReadableArticle>(file(id).readText()).takeIf { it.id == id } }.getOrNull()
    fun save(article: ReadableArticle) {
        require(!article.restricted && article.paragraphs.isNotEmpty())
        directory.mkdirs()
        val atomic = android.util.AtomicFile(file(article.id)); val output = atomic.startWrite()
        try { output.write(json.encodeToString(article).toByteArray()); atomic.finishWrite(output) } catch (error: Exception) { atomic.failWrite(output); throw error }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(50)?.forEach { it.delete() }
    }
    fun remove(id: String) { file(id).delete() }
    suspend fun load(article: Article, refresh: Boolean = false): ReadableArticle {
        if (!refresh) cached(article.id)?.let { return it }
        val url = article.url ?: return ReadableArticle(article.id, article.headline, "", emptyList(), System.currentTimeMillis())
        if (article.isPremium || url.contains("/insider/")) return ReadableArticle(article.id, article.headline, url, emptyList(), System.currentTimeMillis(), restricted = true)
        require(android.net.Uri.parse(url).scheme in setOf("http", "https"))
        val html = http.getString(url, ttlMillis = 300_000, retries = 0)
        return extract(article.id, article.headline, url, html, System.currentTimeMillis())
    }
    companion object {
        fun extract(id: String, title: String, url: String, html: String, now: Long): ReadableArticle {
            // Never run scripts, load hidden/paywalled article bodies, or expose a WebView JS bridge.
            val document = Jsoup.parse(html.take(3_000_000), url)
            val restricted = document.select("script[type=application/ld+json]").any { Regex("\"isAccessibleForFree\"\\s*:\\s*(false|\"false\")", RegexOption.IGNORE_CASE).containsMatchIn(it.data()) }
            if (restricted) return ReadableArticle(id, title, url, emptyList(), now, true)
            document.select("script,style,nav,header,footer,aside,form,button,[hidden],[aria-hidden=true],.advertisement,.ad-container,.related-stories").remove()
            val containers = document.select("[itemprop=articleBody],.article-body,.story-body,.article__body,.article-content,article,main")
            val candidates = containers.map { element -> element.select("p,h2,h3,blockquote").filter { it.parents().none { p -> p.tagName() == "blockquote" } || it.tagName() == "blockquote" }.map { it.text().trim() }.filter { it.length >= 20 }.distinct() }
            val paragraphs = candidates.maxByOrNull { it.sumOf(String::length) }.orEmpty().take(400)
            return ReadableArticle(id, title, url, paragraphs, now)
        }
    }
}

@Composable
fun PhoneArticleReader(article: Article, model: MobileViewModel) {
    val library by model.library.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var body by remember(article.id) { mutableStateOf<ReadableArticle?>(null) }
    var error by remember(article.id) { mutableStateOf<String?>(null) }
    var attempt by remember(article.id) { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val resumeIndex = remember(article.id) { library.readerPositions[article.id] ?: 0 }
    val state = rememberLazyListState()
    var restored by remember(article.id) { mutableStateOf(false) }
    LaunchedEffect(article.id, body) { if (!restored && body?.paragraphs?.isNotEmpty() == true) { state.scrollToItem(resumeIndex.coerceIn(0, body!!.paragraphs.size + 2)); restored = true } }
    val currentIndex by rememberUpdatedState(state.firstVisibleItemIndex)
    val canSavePosition by rememberUpdatedState(restored)
    DisposableEffect(article.id) { onDispose { if (canSavePosition) model.library.update { it.copy(readerPositions = (it.readerPositions + (article.id to currentIndex)).entries.toList().takeLast(200).associate { e -> e.toPair() }) } } }
    LaunchedEffect(article.id, attempt) {
        loading = true; error = null
        try { body = withContext(Dispatchers.IO) { model.articleStore.load(article, refresh = attempt > 0) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "The publisher couldn't be loaded. A saved offline copy remains available if you downloaded one." }
        finally { loading = false }
    }
    val background = when (library.readerAppearance) { "Light" -> Color(0xFFFAF7F4); "Dark" -> Color(0xFF171719); "Sepia" -> Color(0xFFF7EDD6); else -> MaterialTheme.colorScheme.background }
    val foreground = when (library.readerAppearance) { "Light" -> Color(0xFF19191F); "Dark" -> Color(0xFFEBEBEF); "Sepia" -> Color(0xFF42301F); else -> MaterialTheme.colorScheme.onBackground }
    val font = if (library.readerTypeface == "Serif") FontFamily.Serif else FontFamily.Default
    val size = (17 + (library.readerSize.coerceIn(1, 10) - 3) * 2).sp
    val spacing = when (library.readerSpacing) { "Compact" -> 10.dp; "Spacious" -> 24.dp; else -> 16.dp }
    val progress = if (body?.paragraphs.isNullOrEmpty()) 0f else (state.firstVisibleItemIndex.toFloat() / (body!!.paragraphs.size + 3)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize().background(background)) {
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(2.dp), trackColor = background)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { model.library.toggleStory(article) }) { Icon(if (library.savedStories.any { it.id == article.id }) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Save article", tint = foreground) }
            IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.TextFields, "Reader settings", tint = foreground) }
            IconButton(enabled = body?.paragraphs?.isNotEmpty() == true && body?.restricted == false, onClick = {
                val loaded = body ?: return@IconButton
                scope.launch { try { withContext(Dispatchers.IO) { model.articleStore.save(loaded) }; model.library.update { it.copy(offlineStories = (listOf(model.library.savedStory(article)) + it.offlineStories.filterNot { story -> story.id == article.id }).take(50)) }; model.message = "Available offline." } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { model.message = "This article couldn't be saved offline." } }
            }) { Icon(if (library.offlineStories.any { it.id == article.id }) Icons.Rounded.DownloadDone else Icons.Rounded.Download, "Download for offline reading", tint = foreground) }
            IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${article.headline}\n${article.url.orEmpty()}"), "Share article")) }) { Icon(Icons.Rounded.Share, "Share article", tint = foreground) }
            IconButton(onClick = { openBrowser(context, article.url) }) { Icon(Icons.Rounded.OpenInBrowser, "Open publisher", tint = foreground) }
        }
        LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            item { Text(article.source.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
            item { Text(article.headline, color = foreground, fontFamily = font, fontSize = (22 + (library.readerSize - 3) * 2).coerceAtLeast(18).sp, fontWeight = FontWeight.Bold, lineHeight = (30 + (library.readerSize - 3) * 2).coerceAtLeast(24).sp) }
            item { Text(listOfNotNull(article.byline, article.publishedMillis?.let(::dateLabel), body?.paragraphs?.takeIf { it.isNotEmpty() }?.let { paragraphs -> "${(paragraphs.sumOf { it.split(Regex("\\s+")).size } / 238 + 1)} min read" }).joinToString(" · "), color = foreground.copy(alpha = .65f), style = MaterialTheme.typography.labelMedium) }
            if (loading) item { CircularProgressIndicator(Modifier.size(24.dp)) }
            if (error != null) item { Text(error!!, color = foreground); TextButton(onClick = { attempt++ }) { Text("Try again") } }
            if (body?.restricted == true) item { Text("This story requires access from the publisher. Sign in or subscribe on its site to read it.", color = foreground); Button(onClick = { openBrowser(context, article.url) }) { Text("Read with publisher") } }
            if (body?.paragraphs.isNullOrEmpty() && !loading && body?.restricted != true) item { Text(article.description, color = foreground, fontFamily = font, fontSize = size, lineHeight = size * 1.5f); TextButton(onClick = { openBrowser(context, article.url) }) { Text("Continue on publisher site") } }
            itemsIndexed(body?.paragraphs.orEmpty()) { _, paragraph -> SelectionContainer { Text(paragraph, color = foreground, fontFamily = font, fontSize = size, lineHeight = size * when (library.readerSpacing) { "Compact" -> 1.35f; "Spacious" -> 1.8f; else -> 1.55f }) } }
        }
    }
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
        Column(Modifier.padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Reader settings", style = MaterialTheme.typography.titleLarge)
            Text("Text size"); Slider(library.readerSize.toFloat(), { value -> model.library.update { it.copy(readerSize = value.toInt()) } }, valueRange = 1f..10f, steps = 8)
            ChoiceRow(listOf("Auto", "Light", "Dark", "Sepia"), library.readerAppearance) { choice -> model.library.update { it.copy(readerAppearance = choice) } }
            ChoiceRow(listOf("System", "Serif"), library.readerTypeface) { choice -> model.library.update { it.copy(readerTypeface = choice) } }
            ChoiceRow(listOf("Compact", "Comfortable", "Spacious"), library.readerSpacing) { choice -> model.library.update { it.copy(readerSpacing = choice) } }
            TextButton(onClick = { model.library.update { it.copy(readerSize = 3, readerAppearance = "Auto", readerTypeface = "System", readerSpacing = "Comfortable") } }) { Text("Reset reader settings") }
        }
    }
}
