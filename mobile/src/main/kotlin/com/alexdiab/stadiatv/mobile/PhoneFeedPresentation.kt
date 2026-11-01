package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.FeedSections

internal fun phoneFeedUnavailable(feed: FeedSections) = feed.refreshFailed || feed.error != null
internal fun phoneFeedHasKnownCounts(feed: FeedSections) = feed.all.isNotEmpty() || !phoneFeedUnavailable(feed) && !feed.isLoading
internal fun phoneFeedFailureLabel(feed: FeedSections) = if (feed.all.isNotEmpty()) "Saved scores · Tap to refresh" else "Scores unavailable · Tap to retry"
internal fun phoneScheduleEmptyTitle(feed: FeedSections, confirmedEmpty: String = "No games today") = when {
    phoneFeedUnavailable(feed) -> "Schedule unavailable"
    feed.isLoading && feed.all.isEmpty() -> "Loading schedule…"
    else -> confirmedEmpty
}
