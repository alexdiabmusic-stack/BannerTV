package com.alexdiab.stadiatv.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import com.alexdiab.stadiatv.domain.*

/** A followed team. Persisted with its name and crest so Following renders without a network round trip. */
@Immutable
@Serializable
data class FavoriteTeam(
    val leaguePath: String,
    val teamId: String,
    val name: String,
    val abbreviation: String = "",
    val logoUrl: String? = null,
) {
    val key: String get() = "$leaguePath-$teamId"
    val league: League? get() = League.find(leaguePath)

    fun matches(league: League, side: TeamSide): Boolean {
        if (league.path != leaguePath) return false
        if (!side.teamId.isNullOrEmpty() && side.teamId == teamId) return true
        // Display aliases can bridge a legacy provider, but cannot contradict explicit identities
        // from the SAME provider (a recycled abbreviation must not follow the wrong native club).
        val sideId = side.teamId
        if (':' in teamId && sideId?.contains(':') == true && teamId.substringBefore(':').equals(sideId.substringBefore(':'), true)) return false
        if (TeamIdentity.sameAbbreviation(leaguePath, side.abbreviation, abbreviation)) return true
        return name.isNotEmpty() && side.displayName.equals(name, ignoreCase = true)
    }

    fun matches(team: Team): Boolean = matches(team.league, TeamSide(team.name, team.shortName, team.abbreviation, teamId = team.id))

    companion object {
        fun from(team: Team) = FavoriteTeam(team.league.path, team.id, team.name, team.abbreviation, team.logoUrl)
    }
}

/** A multiview arrangement saved to one of the four slots: which channels, in which layout, and which has sound. */
@Immutable
@Serializable
data class SavedMultiview(val channelKeys: List<String>, val layout: String, val audioKey: String? = null)

enum class Appearance(val label: String) { System("System"), Dark("Dark"), Light("Light") }

@Immutable
@Serializable
data class UserSettings(
    val hasCompletedOnboarding: Boolean = false,
    val selectedLeagueIds: Set<String> = emptySet(),
    val favoriteTeams: List<FavoriteTeam> = emptyList(),
    val favoriteChannelIds: Set<String> = emptySet(),
    val preferredStreamLanguages: Set<String> = setOf("en"),
    val spoilerFreeMode: Boolean = false,
    /** Delay applied to score updates so they don't spoil a slightly delayed stream. */
    val sportsScoreDelaySeconds: Int = 0,
    val showLiveScoreBadge: Boolean = true,
    val showChannelNumbers: Boolean = false,
    val playerPanelTimeoutSeconds: Int = 5,
    val preferUhdStreams: Boolean = false,
    /** HD before standard definition when a channel has both. Off prefers standard definition (slow connections, data caps). */
    val preferHdStreams: Boolean = true,
    /** "CA" or "US"; empty follows the device's region. */
    val homeCountry: String = "",
    /** The city whose local stations the guide shows by default ("ottawa"); empty guesses from the device's time zone. */
    val homeMarket: String = "",
    /** Countries whose channels are loaded into the guide, in addition to the home country. */
    val guideCountries: Set<String> = setOf("CA", "US"),
    /** Channels (lineup entry keys) the viewer starred, hid, and ordered by hand. */
    val favoriteEntryKeys: Set<String> = emptySet(),
    val hiddenEntryKeys: Set<String> = emptySet(),
    val entryOrder: List<String> = emptyList(),
    val hideKidsChannels: Boolean = false,
    val showFreeStreaming: Boolean = false,
    /** Spanish-language channels are a separate catalogue, hidden until asked for. */
    val showSpanishChannels: Boolean = false,
    /** Subtitles: on or off at the start of a channel, and the language to choose when they are on. */
    val captionsEnabled: Boolean = false,
    val captionLanguage: String = "",
    /** Global content-language preference, like iOS; never a provider track index. Empty uses device/provider defaults. */
    val preferredAudioLanguage: String = "",
    val guideTimeScaleMinutes: Int = 60,
    val doubleTapSeeks: Boolean = true,
    val playerBufferProfile: String = "Balanced",
    /** Saved multiview layouts keyed by slot "1".."4". */
    val multiviewSlots: Map<String, SavedMultiview> = emptyMap(),
) {
    /** Leagues the user follows, in catalog order. Falls back to the full catalog when nothing is chosen. */
    val followedLeagues: List<League>
        get() = League.ALL.filter { it.path in selectedLeagueIds }.ifEmpty { League.ALL }

    val explicitlyFollowedLeagues: List<League>
        get() = League.ALL.filter { it.path in selectedLeagueIds }

    /** True once the viewer has chosen at least one league; until then every league counts as followed. */
    val followsExplicitly: Boolean get() = selectedLeagueIds.isNotEmpty()

    /** Whether any choice (a league or a team) has been made, which is what Home personalises from. */
    val isPersonalised: Boolean get() = followsExplicitly || favoriteTeams.isNotEmpty()

    /** A game that belongs to the viewer: in a followed league, or played by a starred team in any league. */
    fun inScope(match: Match): Boolean = !followsExplicitly || match.league.path in selectedLeagueIds || involvesFavorite(match)

    fun isFavorite(team: Team): Boolean = favoriteTeams.any { it.matches(team) }

    fun involvesFavorite(match: Match): Boolean {
        if (favoriteTeams.isEmpty()) return false
        return favoriteTeams.any { it.matches(match.league, match.home) || it.matches(match.league, match.away) }
    }

    /** Stable signature of everything the home feed depends on, to know when to reload. */
    val feedSignature: String
        get() = followedLeagues.joinToString(",") { it.path } + "|" + favoriteTeams.joinToString(",") { it.key }
}

