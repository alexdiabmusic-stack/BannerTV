package com.alexdiab.stadiatv.mobile

import android.content.Context
import com.alexdiab.stadiatv.domain.Article
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedStory(val id: String, val title: String, val description: String, val url: String, val image: String?, val source: String, val published: Long? = null, val league: String? = null, val byline: String? = null, val premium: Boolean = false) {
    fun article() = Article(id, title, description, published, url, image, com.alexdiab.stadiatv.domain.League.find(league), byline = byline, source = source, isPremium = premium)
}
@Serializable
data class PhonePreferences(
    val media: com.alexdiab.stadiatv.catalog.MediaLibrary = com.alexdiab.stadiatv.catalog.MediaLibrary(),
    val appearance: String = "System",
    val savedStories: List<SavedStory> = emptyList(),
    val reminders: List<PhoneReminder> = emptyList(),
    val playerBarActions: List<String> = listOf("Guide", "Channels", "Recent"),
    val hiddenStoryIds: Set<String> = emptySet(),
    val mutedNewsSources: Set<String> = emptySet(),
    val readerSize: Int = 3,
    val readerAppearance: String = "Auto",
    val readerTypeface: String = "System",
    val readerSpacing: String = "Comfortable",
    val readerPositions: Map<String, Int> = emptyMap(),
    val offlineStories: List<SavedStory> = emptyList(),
    val followedGolfers: Set<String> = emptySet(),
    val sleeperAccount: com.alexdiab.stadiatv.data.fantasy.SleeperAccount? = null,
    val sleeperLeague: com.alexdiab.stadiatv.data.fantasy.SleeperLeague? = null,
    val sleeperSnapshot: com.alexdiab.stadiatv.data.fantasy.SleeperSnapshot? = null,
    val sleeperPlayers: Map<String, com.alexdiab.stadiatv.data.fantasy.SleeperPlayer> = emptyMap(),
    val matchAlerts: com.alexdiab.stadiatv.data.MatchAlertOptions = com.alexdiab.stadiatv.data.MatchAlertOptions(),
    val notificationLedger: Map<String, Long> = emptyMap(),
    val channelGroups: List<PhoneChannelGroup> = emptyList(),
    val hiddenGameKeys: Set<String> = emptySet(),
    val fantasyDisplay: PhoneFantasyDisplay = PhoneFantasyDisplay(),
)

@Serializable
data class PhoneReminder(val key: String, val title: String, val league: String, val gameId: String, val startsAt: Long, val firesAt: Long, val body: String? = null, val automatic: Boolean = false)

class PhoneLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("phone-library", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(runCatching { json.decodeFromString<PhonePreferences>(prefs.getString("data", null) ?: "{}") }.getOrDefault(PhonePreferences()))
    val state = mutable.asStateFlow()
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "data") prefs.getString("data", null)?.let { raw -> runCatching { json.decodeFromString<PhonePreferences>(raw) }.getOrNull()?.let { mutable.value = it } } }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    @Synchronized fun update(transform: (PhonePreferences) -> PhonePreferences) {
        mutable.value = transform(mutable.value)
        prefs.edit().putString("data", json.encodeToString(mutable.value)).apply()
    }
    fun toggleStory(article: Article) = update { current ->
        val present = current.savedStories.any { it.id == article.id }
        current.copy(savedStories = if (present) current.savedStories.filterNot { it.id == article.id }
            else listOf(savedStory(article)) + current.savedStories)
    }

    fun savedStory(article: Article) = SavedStory(article.id, article.headline, article.description, article.url.orEmpty(), article.imageUrl, article.source, article.publishedMillis, article.league?.path, article.byline, article.isPremium)
}
