package com.alexdiab.stadiatv.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyLibrary
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyLeague
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyEngine
import com.alexdiab.stadiatv.net.AppJson
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

@Serializable data class BackupReading(val size: Int = 3, val appearance: String = "Auto", val typeface: String = "System", val spacing: String = "Comfortable", val positions: Map<String, Int> = emptyMap())
@Serializable data class BackupPodcasts(val subscriptions: List<PodcastShow> = emptyList(), val bookmarks: Map<String, Long> = emptyMap(), val speed: Float = 1f, val advance: Boolean = true, val wifiOnly: Boolean = true)
@Serializable data class BackupWatch(val key: String, val name: String, val guideId: String?, val time: Long)

/** Explicit whitelist. Never serialize playlists, channel objects, credentials or the whole app preferences blob. */
@Serializable data class PhoneCloudBackup(
    val format: String,
    val schema: Int,
    val created: Long,
    val settings: UserSettings,
    val appearance: String,
    val stories: List<SavedStory>,
    val hiddenStories: Set<String>,
    val mutedSources: Set<String>,
    val reading: BackupReading,
    val golfers: Set<String>,
    val podcasts: BackupPodcasts,
    val fantasy: NativeFantasyLibrary,
    val watchHistory: List<BackupWatch> = emptyList(),
    val channelGroups: List<PhoneChannelGroup> = emptyList(),
    val hiddenGames: Set<String> = emptySet(),
    val playerActions: List<String> = com.alexdiab.stadiatv.playback.PlayerQuickActions.defaults,
    val fantasyDisplay: PhoneFantasyDisplay = PhoneFantasyDisplay(),
    val media: com.alexdiab.stadiatv.catalog.MediaLibrary = com.alexdiab.stadiatv.catalog.MediaLibrary(),
) {
    companion object {
        const val FORMAT = "bannertv.android.preferences"
        const val MAX_BYTES = 8 * 1024 * 1024
        /** Keep existing identities and names; reject overflow before changing any store. */
        fun mergeGroups(existing: List<PhoneChannelGroup>, incoming: List<PhoneChannelGroup>): List<PhoneChannelGroup> {
            val merged = existing.toMutableList()
            incoming.forEach { group ->
                if (merged.none { it.id == group.id || it.name.equals(group.name, true) }) merged += group
            }
            require(merged.size <= 20) { "The merged library has more than 20 channel groups." }
            return merged
        }
        fun episodeHash(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun identifier(value: String): Boolean = value.length <= 300 && !value.contains(Regex("(?i)https?[:/]|password=|token=|username="))
        private fun safeMedia(value: com.alexdiab.stadiatv.catalog.MediaLibrary): com.alexdiab.stadiatv.catalog.MediaLibrary {
            fun card(c: com.alexdiab.stadiatv.catalog.MediaCard) = c.copy(title=c.title.take(500),overview=c.overview.take(6000),
                posterPath=c.posterPath?.takeIf { it.matches(Regex("/[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")) },
                backdropPath=c.backdropPath?.takeIf { it.matches(Regex("/[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")) },
                releaseDate=c.releaseDate?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) },genreIds=c.genreIds.filter { it>0 }.distinct().take(30),rating=c.rating?.takeIf { it.isFinite() && it in 0.0..10.0 })
            return com.alexdiab.stadiatv.catalog.MediaLibrary(watchlist=value.watchlist.take(500).map(::card).distinctBy { it.id })
                .merge(com.alexdiab.stadiatv.catalog.MediaLibrary(progress=value.progress.take(100).map { it.copy(card=card(it.card),episodeTitle=it.episodeTitle?.take(500)) }))
        }
        fun publicUrl(value: String?): String? = value?.takeIf { text -> runCatching {
            val uri = URI(text)
            val privateKeys = setOf("password", "pass", "token", "accesstoken", "refreshtoken", "idtoken", "apikey", "key", "auth", "authorization", "username", "signature", "sig", "secret", "credential", "cookie", "session", "sessionid")
            val hasCredentials = uri.rawQuery.orEmpty().split('&').any { pair ->
                URLDecoder.decode(pair.substringBefore('='), "UTF-8").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() } in privateKeys
            }
            uri.scheme in setOf("https", "http") && !uri.host.isNullOrEmpty() && uri.userInfo == null && !hasCredentials
        }.getOrDefault(false) }
        private fun validFantasy(league: NativeFantasyLeague): Boolean {
            if (league.sport !in NativeFantasyEngine.sports || league.teams.size !in 1..12 || league.teams.count { !it.cpu } != 1) return false
            val teamIds = league.teams.map { it.id }
            val roster = league.teams.flatMap { it.roster }
            val userEntries = league.userTeam.roster.map { it.id }.toSet()
            return league.id.isNotBlank() && identifier(league.id) && league.name.isNotBlank() && league.name.length <= 100 &&
                (if (league.personal) league.teams.size == 1 else league.teams.size >= 2) && teamIds.distinct().size == teamIds.size &&
                teamIds.all { it.isNotBlank() && identifier(it) } && league.teams.all { it.name.isNotBlank() && it.name.length <= 80 && it.roster.size <= 60 } &&
                roster.map { it.id }.distinct().size == roster.size && roster.map { it.player.id }.distinct().size == roster.size &&
                roster.all { identifier(it.id) && identifier(it.player.id) && it.player.name.length <= 200 && it.player.eligible.size <= 30 && (it.player.priorPoints?.isFinite() != false) } &&
                league.slots.keys.all { it in NativeFantasyEngine.configurableSlots(league.sport) } && league.slots.values.all { it in 0..20 } &&
                league.slots.filterKeys { it !in setOf("IR", "IL") }.values.sum() in 1..40 &&
                league.scoringType in setOf("Points", "Categories", "Rotisserie") && league.points.keys.all { it in NativeFantasyEngine.points(league.sport) } &&
                league.points.values.all { it.isFinite() && it in -1000.0..1000.0 } && league.categories.all { it in league.points } &&
                league.picks.size <= 480 && league.picks.map { it.number }.distinct().size == league.picks.size &&
                league.picks.all { it.team in teamIds && it.round in 1..40 && it.number in 1..480 && it.player?.let(::identifier) != false } &&
                league.transactions.size <= 1000 && league.transactions.all { it.team in teamIds && it.text.length <= 1000 } &&
                league.lineups.size <= 400 && league.lineups.all { (day, entries) -> day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && entries.size <= 60 && entries.keys.all { it in userEntries } && entries.values.all { it in league.slots } }
        }
        private fun safeFantasy(library: NativeFantasyLibrary) = library.copy(leagues = library.leagues.map { league ->
            league.copy(teams = league.teams.map { team -> team.copy(roster = team.roster.map { entry -> entry.copy(player = entry.player.copy(image = publicUrl(entry.player.image))) }) })
        })
        fun safeSettings(settings: UserSettings): UserSettings = settings.copy(preferredAudioLanguage = com.alexdiab.stadiatv.playback.PlaybackLanguages.normalize(settings.preferredAudioLanguage), captionLanguage = com.alexdiab.stadiatv.playback.PlaybackLanguages.normalize(settings.captionLanguage), favoriteChannelIds = settings.favoriteChannelIds.filter(::identifier).toSet(), favoriteEntryKeys = settings.favoriteEntryKeys.filter(::identifier).toSet(), hiddenEntryKeys = settings.hiddenEntryKeys.filter(::identifier).toSet(), entryOrder = settings.entryOrder.filter(::identifier), multiviewSlots = settings.multiviewSlots.mapValues { (_, value) -> value.copy(channelKeys = value.channelKeys.filter(::identifier), audioKey = value.audioKey?.takeIf(::identifier)) })
        fun create(settings: UserSettings, library: PhonePreferences, podcasts: PodcastLibrary, fantasy: NativeFantasyLibrary, now: Long = System.currentTimeMillis(), history: List<com.alexdiab.stadiatv.live.WatchEntry> = emptyList()): PhoneCloudBackup {
            val subscriptions = podcasts.subscriptions.mapNotNull { show -> publicUrl(show.feed)?.let { show.copy(feed = it, image = publicUrl(show.image)) } }.take(500)
            val bookmarks = podcasts.importedBookmarks + podcasts.bookmarks.mapKeys { episodeHash(it.key) }
            return PhoneCloudBackup(format = FORMAT, schema = 1, created = now, settings = safeSettings(settings), appearance = library.appearance,
                stories = library.savedStories.take(500).mapNotNull { story -> publicUrl(story.url)?.let { story.copy(url = it, image = publicUrl(story.image)) } },
                hiddenStories = library.hiddenStoryIds.filter(::identifier).take(2000).toSet(), mutedSources = library.mutedNewsSources.take(200).toSet(),
                reading = BackupReading(library.readerSize.coerceIn(1, 10), library.readerAppearance, library.readerTypeface, library.readerSpacing, library.readerPositions.filterKeys(::identifier).entries.toList().takeLast(200).associate { it.toPair() }),
                golfers = library.followedGolfers.filter(::identifier).toSet(), podcasts = BackupPodcasts(subscriptions, bookmarks.entries.toList().takeLast(800).associate { it.key to it.value.coerceAtLeast(0) }, podcasts.speed.coerceIn(.5f, 3f), podcasts.automaticallyAdvance, podcasts.wifiDownloadsOnly), fantasy = safeFantasy(fantasy),
                watchHistory = history.take(20).filter { identifier(it.channel.key) }.map { BackupWatch(it.channel.key, it.channel.name, it.channel.tvgId?.takeIf(::identifier), it.lastWatchedMillis) },
                channelGroups = library.channelGroups.take(20).map { it.copy(entries = it.entries.filter(::identifier).take(5000)) }, hiddenGames = library.hiddenGameKeys.filter(::identifier).take(1000).toSet(), playerActions = com.alexdiab.stadiatv.playback.PlayerQuickActions.normalize(library.playerBarActions), fantasyDisplay = library.fantasyDisplay, media=safeMedia(library.media))
        }
        fun decode(text: String): PhoneCloudBackup {
            require(text.toByteArray().size <= MAX_BYTES) { "Backup file is too large." }
            val result = AppJson.decodeFromString<PhoneCloudBackup>(text)
            require(result.format == FORMAT && result.schema == 1) { "This is not a supported BannerTV Android backup." }
            require(result.fantasy.leagues.size <= 20 && result.stories.size <= 500 && result.podcasts.subscriptions.size <= 500 && result.channelGroups.size <= 20) { "Backup contains too many entries." }
            require(result.fantasy.schema == 1 && result.fantasy.leagues.map { it.id }.distinct().size == result.fantasy.leagues.size && result.fantasy.leagues.all(::validFantasy) && (result.fantasy.selected == null || result.fantasy.leagues.any { it.id == result.fantasy.selected })) { "Fantasy league data is invalid." }
            require(result.channelGroups.map { it.id }.distinct().size == result.channelGroups.size && result.channelGroups.map { it.name.lowercase() }.distinct().size == result.channelGroups.size && result.channelGroups.all { it.name.trim().isNotEmpty() && it.name.length <= 60 && it.name.lowercase() !in setOf("all", "all channels", "favorites", "sports") && it.entries.size <= 5000 && it.entries.all(::identifier) }) { "Channel group data is invalid." }
            require(result.podcasts.speed.isFinite())
            require(result.media.watchlist.size <= 500 && result.media.progress.size <= 100) { "Media library contains too many entries." }
            return result.copy(settings = safeSettings(result.settings), fantasy = safeFantasy(result.fantasy), media=safeMedia(result.media), stories = result.stories.filter { publicUrl(it.url) != null }.map { it.copy(image = publicUrl(it.image)) }, podcasts = result.podcasts.copy(subscriptions = result.podcasts.subscriptions.filter { publicUrl(it.feed) != null }.map { it.copy(image = publicUrl(it.image)) }, bookmarks = result.podcasts.bookmarks.filterKeys { it.matches(Regex("[0-9a-f]{64}")) }.mapValues { it.value.coerceAtLeast(0) }, speed = result.podcasts.speed.coerceIn(.5f, 3f)))
        }
    }
}

@Composable
fun CloudBackupScreen(model: MobileViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PhoneCloudBackup?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) scope.launch {
        busy = true; status = null
        try {
            val backup = PhoneCloudBackup.create(model.container.preferences.current, model.library.state.value, model.podcasts.state.value, model.nativeFantasy.state.value, history = model.container.watchHistory.history.value)
            withContext(Dispatchers.IO) {
                val bytes = AppJson.encodeToString(backup).toByteArray(); require(bytes.size <= PhoneCloudBackup.MAX_BYTES)
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
            }
            status = "Backup saved to the location you selected. Keep it private: it contains your preferences and library metadata."
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { status = "The backup couldn't be saved. Check the selected cloud provider or choose a local folder." }
        finally { busy = false }
    } }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        busy = true; status = null
        try { preview = withContext(Dispatchers.IO) {
            val buffer = ByteArrayOutputStream()
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> val chunk = ByteArray(32768); while (true) { ensureActive(); val count = input.read(chunk); if (count < 0) break; require(buffer.size().toLong() + count <= PhoneCloudBackup.MAX_BYTES); buffer.write(chunk, 0, count) } }
            PhoneCloudBackup.decode(buffer.toString("UTF-8"))
        } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { status = "This file couldn't be read as a supported BannerTV backup. Nothing has been changed." }
        finally { busy = false }
    } }
    ScreenList {
        item { Text("Your library, backed up.", style = MaterialTheme.typography.headlineMedium) }
        item { PhoneCard {
            Text("Choose a cloud document provider or local folder using Android's file picker.")
            Text("Includes preferences, followed teams, channel/history IDs, saved-story metadata, reading settings, podcast subscriptions/bookmarks and local fantasy leagues.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Excludes playlist logins, IPTV stream URLs, ESPN cookies, Sleeper account connections, reminders, downloaded media and offline article bodies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("This is manual backup/restore, not real-time sync. Files are not encrypted by BannerTV; use a private cloud folder. The file format is Android-specific.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { Button(enabled = !busy, onClick = { save.launch("BannerTV-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json") }, modifier = Modifier.fillMaxWidth()) { Text("Create backup file") } }
        item { OutlinedButton(enabled = !busy, onClick = { load.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.fillMaxWidth()) { Text("Preview a backup to restore") } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        status?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
    }
    preview?.let { backup -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("Restore this backup?") }, text = { Text("Saved ${dateLabel(backup.created)} · ${backup.settings.favoriteTeams.size} teams · ${backup.stories.size} stories · ${backup.podcasts.subscriptions.size} podcasts · ${backup.fantasy.leagues.size} local leagues.\n\nGeneral preferences are applied. Favorites and libraries are merged; existing local leagues win on ID conflicts. Playlists and logins are left untouched.") }, confirmButton = { TextButton(onClick = {
        try {
            // Build every merged value before writing any store, so invalid imports cannot half-apply.
            val currentSettings = model.container.preferences.current
            val mergedSettings = backup.settings.copy(hasCompletedOnboarding = currentSettings.hasCompletedOnboarding, favoriteTeams = (currentSettings.favoriteTeams + backup.settings.favoriteTeams).distinctBy { it.key }, selectedLeagueIds = currentSettings.selectedLeagueIds + backup.settings.selectedLeagueIds, favoriteChannelIds = currentSettings.favoriteChannelIds + backup.settings.favoriteChannelIds, favoriteEntryKeys = currentSettings.favoriteEntryKeys + backup.settings.favoriteEntryKeys)
            val currentFantasy = model.nativeFantasy.state.value
            val mergedFantasy = (currentFantasy.leagues + backup.fantasy.leagues).distinctBy { it.id }; require(mergedFantasy.size <= 20)
            val mergedGroups = PhoneCloudBackup.mergeGroups(model.library.state.value.channelGroups, backup.channelGroups)
            val channels = model.container.playlists.state.value.channels
            val history = backup.watchHistory.take(20).mapNotNull { entry ->
                val channel = channels.firstOrNull { it.key == entry.key } ?: entry.guideId?.let { id -> channels.filter { it.tvgId == id }.singleOrNull() } ?: channels.filter { it.name == entry.name }.singleOrNull()
                channel?.let { com.alexdiab.stadiatv.live.WatchEntry(it, entry.time.coerceIn(0, System.currentTimeMillis())) }
            }
            model.nativeFantasy.update { it.copy(leagues = mergedFantasy, selected = it.selected ?: backup.fantasy.selected) }
            model.container.preferences.update { mergedSettings }
            model.library.update { old -> old.copy(appearance = backup.appearance.takeIf { it in setOf("System", "Light", "Dark") } ?: old.appearance, savedStories = (old.savedStories + backup.stories).distinctBy { it.id }.take(500), hiddenStoryIds = old.hiddenStoryIds + backup.hiddenStories, mutedNewsSources = old.mutedNewsSources + backup.mutedSources, readerSize = backup.reading.size.coerceIn(1, 10), readerAppearance = backup.reading.appearance, readerTypeface = backup.reading.typeface, readerSpacing = backup.reading.spacing, readerPositions = backup.reading.positions + old.readerPositions, followedGolfers = old.followedGolfers + backup.golfers) }
            model.podcasts.update { old -> old.copy(subscriptions = (old.subscriptions + backup.podcasts.subscriptions).distinctBy { it.feed }.take(500), importedBookmarks = backup.podcasts.bookmarks + old.importedBookmarks, automaticallyAdvance = backup.podcasts.advance, wifiDownloadsOnly = backup.podcasts.wifiOnly, speed = backup.podcasts.speed) }
            model.container.watchHistory.merge(history)
            model.library.update { old -> old.copy(channelGroups = mergedGroups, hiddenGameKeys = old.hiddenGameKeys + backup.hiddenGames, playerBarActions = com.alexdiab.stadiatv.playback.PlayerQuickActions.normalize(backup.playerActions), fantasyDisplay = backup.fantasyDisplay, media=old.media.merge(backup.media)) }
            status = "Backup restored. Your playlist logins and existing downloads were not changed."
        } catch (_: Exception) { status = "The backup could not be applied. Check that the merged library stays within the app's limits." }
        preview = null
    }) { Text("Restore") } }, dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } }) }
}
