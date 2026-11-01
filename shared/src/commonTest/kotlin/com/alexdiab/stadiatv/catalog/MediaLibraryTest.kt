package com.alexdiab.stadiatv.catalog

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class MediaLibraryTest {
    private val card=MediaCard(MediaId(MediaKind.Series,42),"Example","",null,null,"2020-01-01",null)
    @Test fun episodeProgressIsSeparateAndCompletedEpisodesLeaveContinueWatching() {
        val first=MediaProgress(card,MediaVideo(card.id,1,1),40_000,100_000,1)
        val second=first.copy(video=MediaVideo(card.id,1,2),updatedMillis=2)
        val library=MediaLibrary().record(first).record(second).record(first.copy(positionMillis=96_000,updatedMillis=3))
        assertEquals(2,library.progress.size); assertEquals(2,library.continueWatching.single().video.episode)
        assertTrue(library.progress.first().completed)
    }
    @Test fun importKeepsLatestProgressAndNeverPersistsAStreamUrl() {
        val old=MediaProgress(card,MediaVideo(card.id,0,1),40_000,100_000,1)
        val current=old.copy(positionMillis=70_000,updatedMillis=2)
        val merged=MediaLibrary().toggle(card).record(current).merge(MediaLibrary().toggle(card).record(old))
        assertEquals(1,merged.watchlist.size); assertEquals(70_000L,merged.progress.single().positionMillis)
        val text=Json.encodeToString(merged)
        assertEquals(merged,Json.decodeFromString<MediaLibrary>(text)); assertFalse(text.contains("url"))
        assertTrue(merged.toggle(card).watchlist.isEmpty())
    }
    @Test fun invalidAndUnknownDurationProgressDoesNotOverwriteKnownResumePoint() {
        val valid=MediaProgress(card,MediaVideo(card.id,1,1),40_000,100_000,1)
        val library=MediaLibrary().record(valid)
        assertEquals(library,library.record(valid.copy(durationMillis=0)))
        assertEquals(library,library.record(valid.copy(video=MediaVideo(card.id))))
        assertEquals(library,library.record(valid.copy(positionMillis=-1)))
        assertFalse(valid.copy(positionMillis=12_000).resumable)
    }
}
