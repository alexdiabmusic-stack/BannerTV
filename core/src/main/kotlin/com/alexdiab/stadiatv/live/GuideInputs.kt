package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.lineup.ResolvedStream

/** Never compact/persist the full provider guide against an incomplete startup lineup. */
internal fun guideInputsReady(streams: List<ResolvedStream>, playlists: PlaylistsState): Boolean {
    if (!playlists.hasLoadedCache || playlists.channels.isEmpty() || streams.size != playlists.channels.size) return false
    // Counts alone miss a same-sized replacement, a renewed URL/header, or a changed guide ID.
    return streams.indices.all { streams[it].channel == playlists.channels[it] }
}
