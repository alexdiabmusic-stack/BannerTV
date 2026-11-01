package com.alexdiab.stadiatv.mobile

/** Stable screen identities, independent of changing scores/bios. Never serialize an entire page/model (which
 * can contain provider URLs) into a saveable-state key. Only a digest reaches the Android state bundle. */
fun phonePageStateKey(page: PhonePage): String {
    val identity = when (page) {
        is PhonePage.Game -> "game:${page.match.key}"
        is PhonePage.LeagueHub -> "league:${page.league.path}:${page.initialSection}${page.nflWeek?.key?.let { ":$it" }.orEmpty()}${page.calendarDate?.let { ":date:$it" }.orEmpty()}"
        is PhonePage.TeamDetail -> "team:${page.team.league.path}:${page.team.id}"
        is PhonePage.PlayerDetail -> "player:${page.league.path}:${page.athlete.id}"
        is PhonePage.PlaylistForm -> "playlist-form:${page.editing?.id ?: "new"}"
        is PhonePage.Reader -> "reader:${page.article.source}:${page.article.id}"
        is PhonePage.Podcast -> "podcast:${page.show.feed}"
        is PhonePage.Golf -> "golf:${page.id}"
        is PhonePage.Stats -> "stats:${page.leaguePath}"
        is PhonePage.FollowingSchedule -> "schedule:${page.entity}:${page.sport}"
        PhonePage.Search -> "search"
        PhonePage.Teams -> "teams"
        PhonePage.Playlists -> "playlists"
        PhonePage.Fantasy -> "fantasy"
        PhonePage.SavedArticles -> "saved-articles"
        PhonePage.History -> "watch-history"
        PhonePage.Multiview -> "multiview"
        PhonePage.Podcasts -> "podcasts"
        PhonePage.AudioPlayer -> "audio-player"
        PhonePage.CloudBackup -> "cloud-backup"
        PhonePage.Addons -> "entertainment"
        PhonePage.Cinema -> "cinema"
        is PhonePage.CinemaDetail -> "cinema:${page.id.kind.path}:${page.id.value}"
        PhonePage.ChannelGroups -> "channel-groups"
        PhonePage.Premium -> "premium"
    }
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return "page:" + buildString(64) { hash.forEach { byte -> val n = byte.toInt() and 255; append(hex[n ushr 4]); append(hex[n and 15]) } }
}
