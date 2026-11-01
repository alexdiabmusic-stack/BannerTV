package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.*
import com.alexdiab.stadiatv.data.fantasy.*
import com.alexdiab.stadiatv.net.AppJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PhoneCloudBackupTest {
    @Test fun mediaWatchlistAndEpisodeResumeRoundTripWithoutArbitraryArtworkUrls() {
        val id=com.alexdiab.stadiatv.catalog.MediaId(com.alexdiab.stadiatv.catalog.MediaKind.Series,12)
        val card=com.alexdiab.stadiatv.catalog.MediaCard(id,"Example","", "/poster.jpg","https://private.example/art?token=private", "2020-01-01",7.5)
        val media=com.alexdiab.stadiatv.catalog.MediaLibrary().toggle(card).record(com.alexdiab.stadiatv.catalog.MediaProgress(card,com.alexdiab.stadiatv.catalog.MediaVideo(id,1,2),40_000,100_000,1000))
        val backup=PhoneCloudBackup.create(UserSettings(),PhonePreferences(media=media),PodcastLibrary(),NativeFantasyLibrary())
        val encoded=AppJson.encodeToString(backup)
        assertFalse(encoded.contains("token=private"))
        val restored=PhoneCloudBackup.decode(encoded).media
        assertEquals(2,restored.continueWatching.single().video.episode); assertEquals(1,restored.watchlist.size)
        assertEquals("/poster.jpg",restored.watchlist.single().posterPath); assertNull(restored.watchlist.single().backdropPath)
    }
    @Test fun maximumConfiguredDraftAndReserveRosterCanBeBackedUpAndIndexed() {
        var league = NativeFantasyEngine.create("Large constructed league", "Team", "nhl", true, roster = mapOf("C" to 20, "BN" to 20, "IR" to 20))
        val players = (1..60).map { NativeFantasyPlayer("nhl:$it", "Constructed player $it", "LAK", "C", setOf("C", "F", "UTIL"), injury = if (it > 40) "Reported injury" else null) }
        for (person in players.take(40)) league = NativeFantasyEngine.draft(league, person, players)
        for (person in players.drop(40)) league = NativeFantasyEngine.add(league, person)
        for ((index, entry) in league.userTeam.roster.withIndex()) league = NativeFantasyEngine.move(league, "2026-10-24", entry.id, when { index < 20 -> "C"; index < 40 -> "BN"; else -> "IR" })
        val library = NativeFantasyLibrary(selected = league.id, leagues = listOf(league))
        val backup = PhoneCloudBackup.create(UserSettings(), PhonePreferences(), PodcastLibrary(), library)
        assertEquals(library, PhoneCloudBackup.decode(AppJson.encodeToString(backup)).fantasy)
        assertEquals(60, NativeFantasyContexts.stamp(league, com.alexdiab.stadiatv.util.Dates.parseIso("2026-10-24T16:00:00Z")!!)!!.entries.size)
    }
    @Test fun nativeAndLegacyDraftIdsAndCustomForwardSlotsSurviveBackupWithoutMigration() {
        for (id in listOf("nhl:123", "espn-nhl-123")) {
            val player = NativeFantasyPlayer(id, "Constructed player", "ANA", "L", NativeFantasyEngine.eligible("L", "nhl"))
            val league = NativeFantasyEngine.create("Roundtrip", "Team", "nhl", true, roster = mapOf("F" to 1, "BN" to 1))
            val drafted = NativeFantasyEngine.draft(league, player, listOf(player))
            val moved = NativeFantasyEngine.move(drafted, "2026-10-24", drafted.userTeam.roster.single().id, "F")
            val library = NativeFantasyLibrary(selected = league.id, leagues = listOf(moved))
            val backup = PhoneCloudBackup.create(UserSettings(), PhonePreferences(), PodcastLibrary(), library)
            val restored = PhoneCloudBackup.decode(AppJson.encodeToString(backup)).fantasy
            assertEquals(library, restored)
            assertEquals(NativeFantasyPoolPolicy.key(moved), NativeFantasyPoolPolicy.key(restored.leagues.single()))
        }
    }
    @Test fun fantasyPortraitUrlsFollowTheSameCredentialPrivacyRulesOnExportAndImport() {
        val unsafeImage = "https://images.example/player?token=portrait-secret"
        val player = NativeFantasyPlayer("nhl:123", "Constructed player", "ANA", "C", setOf("C"), image = unsafeImage)
        val league = NativeFantasyEngine.create("Portraits", "Team", "nhl", true, roster = mapOf("BN" to 1))
        val drafted = NativeFantasyEngine.draft(league, player, listOf(player))
        val library = NativeFantasyLibrary(selected = league.id, leagues = listOf(drafted))
        val backup = PhoneCloudBackup.create(UserSettings(), PhonePreferences(), PodcastLibrary(), library)
        assertFalse(AppJson.encodeToString(backup).contains("portrait-secret"))
        val imported = PhoneCloudBackup.decode(AppJson.encodeToString(backup.copy(fantasy = library))).fantasy
        assertNull(imported.leagues.single().userTeam.roster.single().player.image)
        assertEquals(player.id, imported.leagues.single().userTeam.roster.single().player.id)
    }
    @Test fun audioLanguageIsNormalizedRoundTrippedAndDoesNotImportArbitraryProviderText() {
        val backup = PhoneCloudBackup.create(UserSettings(preferredAudioLanguage = "fre_CA"), PhonePreferences(), PodcastLibrary(), NativeFantasyLibrary())
        assertEquals("fr-CA", PhoneCloudBackup.decode(AppJson.encodeToString(backup)).settings.preferredAudioLanguage)
        val unsafe = backup.copy(settings = backup.settings.copy(preferredAudioLanguage = "https://provider.example/password=secret"))
        assertEquals("", PhoneCloudBackup.decode(AppJson.encodeToString(unsafe)).settings.preferredAudioLanguage)
    }
    @Test fun explicitWhitelistExcludesProviderStreamsAndCredentials() {
        val stream = "http://provider.example/live/test-user/test-password/1.ts"
        val backup = PhoneCloudBackup.create(
            UserSettings(favoriteChannelIds = setOf("playlist|channel", stream)),
            PhonePreferences(sleeperAccount = com.alexdiab.stadiatv.data.fantasy.SleeperAccount("private-id", "private-user", "Name"), reminders = listOf(PhoneReminder("key", "Title", "league", "id", 1, 1)), savedStories = listOf(SavedStory("id", "Headline", "Summary", "https://news.example/story", null, "Publisher"))),
            PodcastLibrary(queue = listOf(PhoneEpisode("episode", "https://feed.example/rss", "Show", "Episode", "", stream)), bookmarks = mapOf(stream to 12345)), NativeFantasyLibrary(), 1000)
        val text = AppJson.encodeToString(backup)
        assertFalse(text.contains("test-password")); assertFalse(text.contains("test-user")); assertFalse(text.contains("private-id")); assertFalse(text.contains("private-user"))
        assertEquals(setOf("playlist|channel"), backup.settings.favoriteChannelIds)
        assertEquals(12345L, backup.podcasts.bookmarks[PhoneCloudBackup.episodeHash(stream)])
        assertEquals(1, PhoneCloudBackup.decode(text).stories.size)
    }
    @Test fun tokenFeedsAndUserInfoAreNotExported() {
        for (url in listOf("https://user:secret@feed.example/rss", "https://feed.example/rss?token=secret", "file:///private/secret", "https://feed.example/rss?password=secret", "https://feed.example/rss?%74oken=secret", "https://feed.example/rss?access_token=secret", "https://feed.example/rss?API%5FKEY=secret")) assertNull(PhoneCloudBackup.publicUrl(url))
        assertEquals("https://feed.example/rss?show=12", PhoneCloudBackup.publicUrl("https://feed.example/rss?show=12"))
    }
    @Test fun wrongFormatIsRejectedBeforeRestore() {
        val backup = PhoneCloudBackup.create(UserSettings(), PhonePreferences(), PodcastLibrary(), NativeFantasyLibrary())
        val text = AppJson.encodeToString(backup.copy(format = "another-app"))
        assertThrows(IllegalArgumentException::class.java) { PhoneCloudBackup.decode(text) }
        assertThrows(IllegalArgumentException::class.java) { PhoneCloudBackup.decode(AppJson.encodeToString(backup.copy(schema = 99))) }
    }
    @Test fun exportAlwaysContainsFormatAndSchemaAndRejectsUnidentifiedFiles() {
        val backup = PhoneCloudBackup.create(UserSettings(), PhonePreferences(playerBarActions = listOf("PiP", "Guide")), PodcastLibrary(), NativeFantasyLibrary())
        val text = AppJson.encodeToString(backup)
        assertTrue(text.contains("\"format\":\"${PhoneCloudBackup.FORMAT}\""))
        assertTrue(text.contains("\"schema\":1"))
        assertEquals(listOf("PiP", "Guide"), PhoneCloudBackup.decode(text).playerActions)
        assertThrows(IllegalArgumentException::class.java) { PhoneCloudBackup.decode(text.replace("\"schema\":1,", "")) }
    }
    @Test fun groupRestoreKeepsExistingNamesAndRejectsOverflowWithoutDroppingGroups() {
        val existing = PhoneChannelGroup("local", "Game night", listOf("tsn1"))
        val incoming = PhoneChannelGroup("cloud", "GAME NIGHT", listOf("tsn5"))
        assertEquals(listOf(existing), PhoneCloudBackup.mergeGroups(listOf(existing), listOf(incoming)))
        val full = (1..20).map { PhoneChannelGroup("$it", "Group $it", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PhoneCloudBackup.mergeGroups(full, listOf(incoming)) }
        assertEquals(20, full.size)
    }
    @Test fun corruptFantasyReferencesAreRejectedBeforeTheyCanCrashTheUi() {
        val league = com.alexdiab.stadiatv.data.fantasy.NativeFantasyEngine.create("Test", "Team", "nhl", true)
        val backup = PhoneCloudBackup.create(UserSettings(), PhonePreferences(), PodcastLibrary(), NativeFantasyLibrary(selected = league.id, leagues = listOf(league)))
        assertEquals(1, PhoneCloudBackup.decode(AppJson.encodeToString(backup)).fantasy.leagues.size)
        val broken = league.copy(picks = league.picks.map { it.copy(team = "missing-team") })
        assertThrows(IllegalArgumentException::class.java) { PhoneCloudBackup.decode(AppJson.encodeToString(backup.copy(fantasy = backup.fantasy.copy(leagues = listOf(broken))))) }
        assertThrows(IllegalArgumentException::class.java) { PhoneCloudBackup.decode(AppJson.encodeToString(backup.copy(fantasy = backup.fantasy.copy(selected = "missing-league")))) }
    }
}
