package com.alexdiab.stadiatv.playback

import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.net.AppJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PlaybackPresentationTest {
    @Test fun startupRecoveryRespectsManualPauseAndPlayingAudioOnlySources() {
        assertTrue(PlaybackStartupPolicy.shouldRecover(false, false, false))
        assertFalse(PlaybackStartupPolicy.shouldRecover(false, false, true))
        assertFalse(PlaybackStartupPolicy.shouldRecover(false, true, false))
        assertFalse(PlaybackStartupPolicy.shouldRecover(true, false, false))
    }
    @Test fun originalNeverUpscalesAndUsesPhysicalPixels() {
        assertEquals(VideoFrameSize(640f, 360f), VideoSizing.original(640, 360, 1f, 2000f, 1600f))
        // iOS's 1000 x 800 points at 2x display scale: 2000 x 1600 physical pixels.
        val reduced = VideoSizing.original(3840, 2160, 1f, 2000f, 1600f)
        assertEquals(2000f, reduced.width, .001f); assertEquals(1125f, reduced.height, .001f)
        assertEquals(VideoFrameSize(720f, 540f), VideoSizing.original(720, 540, 1f, 1000f, 800f))
    }
    @Test fun originalHandlesAnamorphicPortraitAndUnknownSizes() {
        assertEquals(VideoFrameSize(960f, 540f), VideoSizing.original(720, 540, 4f / 3, 1600f, 900f))
        assertEquals(VideoFrameSize(270f, 480f), VideoSizing.original(1080, 1920, 1f, 600f, 480f))
        assertEquals(VideoFrameSize(600f, 480f), VideoSizing.original(0, 0, 1f, 600f, 480f))
        assertEquals(VideoFrameSize(640f, 360f), VideoSizing.original(640, 360, Float.NaN, 900f, 600f))
        assertEquals(VideoFrameSize(0f, 0f), VideoSizing.original(640, 360, 1f, Float.NaN, Float.POSITIVE_INFINITY))
    }
    @Test fun aspectNamesRemainCompatibleWhileLabelsMatchTheirActualEffect() {
        assertEquals("Stretch", AspectMode.valueOf("Fill").label)
        assertEquals("Fill", AspectMode.valueOf("Zoom").label)
        assertEquals(listOf("Fit", "Fill", "Stretch", "Original"), AspectMode.entries.map { it.label })
    }
    @Test fun languageCodesNormalizeIsoAliasesAndRegionWithoutAcceptingUrlsOrUnknown() {
        assertEquals("fr-CA", PlaybackLanguages.normalize(" FRE_ca "))
        assertEquals("en", PlaybackLanguages.normalize("eng"))
        assertEquals("pl-PL", PlaybackLanguages.normalize("pol-PL"))
        assertEquals("vi", PlaybackLanguages.normalize("vie"))
        assertEquals("zh-Hant-TW", PlaybackLanguages.normalize("zho-Hant-TW"))
        for (value in listOf("und", "zxx", "mul", "https://provider.example", "en?token=secret", "x-private", "")) assertEquals("", PlaybackLanguages.normalize(value))
    }
    @Test fun preferredSubtitleActuallyChangesLanguageEvenWhenAnotherTrackIsAlreadySelected() {
        val tracks = listOf(TrackOption("0", "English", true, "eng"), TrackOption("1", "French Canada", false, "fr-CA"), TrackOption("2", "French", false, "fra"))
        assertEquals("1", PlaybackLanguages.preferred(tracks, "fr-CA")?.id)
        assertEquals("2", PlaybackLanguages.preferred(tracks, "fr")?.id)
        assertEquals("1", PlaybackLanguages.preferred(tracks.take(2), "fr-FR")?.id)
        assertEquals("0", PlaybackLanguages.preferred(tracks, "ja")?.id)
        assertNull(PlaybackLanguages.preferred(emptyList(), "en"))
    }
    @Test fun sameLanguageManualChoiceIsNotOverwrittenByAutomaticCaptionSelection() {
        val tracks = listOf(TrackOption("0", "Normal", false, "en"), TrackOption("1", "Commentary", true, "eng"))
        assertEquals("1", PlaybackLanguages.preferred(tracks, "en")?.id)
    }
    @Test fun oldTrackMenuCannotSelectReusedIndicesAndMalformedIdsStayInvalid() {
        assertEquals(2 to 3, TrackSelectionIdentity.indices(TrackSelectionIdentity.encode(10, 2, 3), 10))
        for (id in listOf("9:2:3", "10:-1:0", "10:2:3:4", "10:x:1", "0:1", "10:2147483648:0")) assertNull(TrackSelectionIdentity.indices(id, 10))
    }
    @Test fun audioPreferenceSurvivesSettingsRoundTripAndOldFilesStillLoad() {
        val old = AppJson.decodeFromString<UserSettings>("{}")
        assertEquals("", old.preferredAudioLanguage)
        assertEquals("fr-CA", AppJson.decodeFromString<UserSettings>(AppJson.encodeToString(old.copy(preferredAudioLanguage = "fr-CA"))).preferredAudioLanguage)
    }
}
