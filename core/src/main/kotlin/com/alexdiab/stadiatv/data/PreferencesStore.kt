package com.alexdiab.stadiatv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.domain.Team
import com.alexdiab.stadiatv.domain.TeamSide
import com.alexdiab.stadiatv.domain.TeamIdentity
import com.alexdiab.stadiatv.net.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Single source of truth for user settings. Everything is one JSON document (like the iOS
 * `UserPreferences`), exposed as a [StateFlow] so Compose recomposes only what changed.
 * Keys written by the original Android build are migrated on first launch.
 */
class PreferencesStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    val current: UserSettings get() = _settings.value

    fun update(transform: (UserSettings) -> UserSettings) {
        _settings.update(transform)
        persist()
    }

    fun completeOnboarding() = update { it.copy(hasCompletedOnboarding = true) }
    fun resetOnboarding() = update { it.copy(hasCompletedOnboarding = false) }

    fun toggleLeague(league: League) = update {
        it.copy(selectedLeagueIds = if (league.path in it.selectedLeagueIds) it.selectedLeagueIds - league.path else it.selectedLeagueIds + league.path)
    }

    /** [pruneTeams] also drops starred teams from leagues that are no longer chosen, as setup does when it narrows the leagues. */
    fun setLeagues(paths: Set<String>, pruneTeams: Boolean = false) = update {
        it.copy(selectedLeagueIds = paths, favoriteTeams = if (pruneTeams) Favourites.pruneTeams(it.favoriteTeams, paths) else it.favoriteTeams)
    }

    /** A sport is chosen as a whole: all of its leagues on, or all off. */
    fun toggleSport(group: SportGroup) = update { it.copy(selectedLeagueIds = Favourites.toggleSport(it.selectedLeagueIds, group)) }

    fun toggleFavorite(team: Team) = update { settings ->
        val existing = settings.favoriteTeams.firstOrNull { it.matches(team) }
        settings.copy(
            favoriteTeams = if (existing != null) settings.favoriteTeams - existing else settings.favoriteTeams + FavoriteTeam.from(team),
        )
    }

    fun addFavorite(team: FavoriteTeam) = update { if (it.favoriteTeams.any { f -> f.key == team.key }) it else it.copy(favoriteTeams = it.favoriteTeams + team) }

    fun removeFavorite(team: FavoriteTeam) = update { settings -> settings.copy(favoriteTeams = settings.favoriteTeams.filterNot { it.key == team.key }) }

    fun toggleFavoriteEntry(key: String) = update {
        it.copy(favoriteEntryKeys = if (key in it.favoriteEntryKeys) it.favoriteEntryKeys - key else it.favoriteEntryKeys + key)
    }

    fun toggleHiddenEntry(key: String) = update {
        it.copy(hiddenEntryKeys = if (key in it.hiddenEntryKeys) it.hiddenEntryKeys - key else it.hiddenEntryKeys + key)
    }

    fun toggleFavoriteChannel(channelId: String) = update {
        it.copy(favoriteChannelIds = if (channelId in it.favoriteChannelIds) it.favoriteChannelIds - channelId else it.favoriteChannelIds + channelId)
    }

    private fun persist() {
        prefs.edit().putString(KEY_SETTINGS, AppJson.encodeToString(_settings.value)).apply()
    }

    private fun load(): UserSettings {
        prefs.getString(KEY_SETTINGS, null)?.let { stored ->
            runCatching { return AppJson.decodeFromString<UserSettings>(stored) }
        }
        return migrateLegacy().also { settings ->
            prefs.edit().putString(KEY_SETTINGS, AppJson.encodeToString(settings)).apply()
        }
    }

    /** Reads the v1 keys the original Android build wrote, so an update never loses someone's setup. */
    private fun migrateLegacy(): UserSettings {
        val legacy = prefs.getBoolean("onboarding", false) || prefs.contains("sports") || prefs.contains("leagues")
        if (!legacy) return UserSettings()
        val leagues = prefs.getStringSet("leagues", emptySet()).orEmpty().filter { League.find(it) != null }.toSet()
        val favorites = prefs.getStringSet("favoriteTeams", emptySet()).orEmpty().mapNotNull { raw ->
            val league = League.ALL.firstOrNull { raw.startsWith(it.path + "-") } ?: return@mapNotNull null
            val teamId = raw.removePrefix(league.path + "-")
            // Display details are backfilled from the team list the next time Following opens.
            FavoriteTeam(league.path, teamId, name = "")
        }
        return UserSettings(
            hasCompletedOnboarding = prefs.getBoolean("onboarding", false),
            selectedLeagueIds = leagues,
            favoriteTeams = favorites,
            favoriteChannelIds = prefs.getStringSet("favoriteChannels", emptySet()).orEmpty().toSet(),
            preferredStreamLanguages = prefs.getStringSet("streamLanguages", null)?.toSet() ?: setOf("en"),
            spoilerFreeMode = prefs.getBoolean("spoilerFree", false),
            showLiveScoreBadge = prefs.getBoolean("liveScoreOverlay", true),
        )
    }

    private companion object {
        const val FILE = "stadiatv.preferences.v1"
        const val KEY_SETTINGS = "settings.v2"
    }
}
