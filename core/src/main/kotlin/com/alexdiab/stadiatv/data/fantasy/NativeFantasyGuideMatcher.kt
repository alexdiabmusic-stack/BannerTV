package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.live.*
import java.util.Locale
import kotlin.math.abs

/** A programme badge requires this exact playlist listing, not a channel-wide or ±2-hour association. */
object NativeFantasyGuideMatcher {
    fun players(resolvedGuideId: String?, programme: Programme, index: NativeFantasyEventIndex): List<NativeFantasyContextPlayer> {
        fun normalized(id: String?) = id.orEmpty().trim().lowercase(Locale.ROOT)
        val binding = normalized(resolvedGuideId)
        if (binding.isEmpty() || binding != normalized(programme.guideId) || programme.endMillis <= programme.startMillis ||
            programme.endMillis - programme.startMillis > 6 * 3_600_000L || index.owner == null) return emptyList()
        // Empty stream name/category deliberately prevent event-channel/broadcaster inference. The only
        // evidence in this matcher is the supplied programme's own title and description.
        val linker = StreamLinker(listOf(LinkerStream("listing", "", "", programme.guideId)),
            listOf(LinkerProgramme(programme.guideId, programme.startMillis, programme.endMillis, programme.title, programme.description)))
        return index.contexts.asSequence().filter { abs(it.match.startMillis - programme.startMillis) <= 12 * 3_600_000L }
            .filter { context -> linker.link(StreamAvailabilityStore.event(context.match)).any { it.confidence >= .7 && it.tier in setOf(LinkTier.LiveListing, LinkTier.ListingByDescription) } }
            .flatMap { it.players.asSequence() }.distinctBy { it.entry.id }.toList()
    }
}
