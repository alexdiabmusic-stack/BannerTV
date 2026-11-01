package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.FavoriteTeam
import com.alexdiab.stadiatv.data.news.TeamPodcastResolver
import com.alexdiab.stadiatv.domain.League

internal data class PhonePodcastTopic(val id: String, val label: String, val league: League? = null, val team: FavoriteTeam? = null)

/** Like iOS, followed teams sit next to their league; identity is scoped, never a name-only key. */
internal fun phonePodcastTopics(favorites: List<FavoriteTeam>, followed: List<League>): List<PhonePodcastTopic> = buildList {
    add(PhonePodcastTopic("all", "All sports"))
    val major = listOf("hockey/nhl", "football/nfl", "basketball/nba", "baseball/mlb", "soccer/eng.1", "soccer/usa.1")
    val leagues = (major.mapNotNull(League::find) + followed + favorites.mapNotNull { it.league }).distinctBy { it.path }
    for (league in leagues) {
        add(PhonePodcastTopic("league:${league.path}", league.shortName, league))
        for (team in favorites.distinctBy { it.key }.filter { it.leaguePath == league.path })
            add(PhonePodcastTopic("team:${team.key}", "${team.name} · ${league.shortName}", league, team))
    }
}

internal fun phonePodcastCatalog(topic: PhonePodcastTopic, shows: List<PodcastShow>, followed: List<League>): List<PodcastShow> {
    val team = topic.team
    if (team != null) {
        val name = TeamPodcastResolver.normalize(team.name)
        if (name.isBlank()) return emptyList()
        return shows.filter { show -> show.tags.any { TeamPodcastResolver.normalize(it) == name } ||
            " ${TeamPodcastResolver.normalize(show.title)} ".contains(" $name ") }
    }
    val sports = (topic.league?.let(::listOf) ?: followed).map { it.group.label.lowercase() }.toSet()
    return shows.filter { show -> sports.isEmpty() || sports.any { sport -> show.sport.lowercase().contains(sport) ||
        show.tags.any { it.lowercase().contains(sport) } } }.take(if (topic.league == null) 80 else 40)
}
