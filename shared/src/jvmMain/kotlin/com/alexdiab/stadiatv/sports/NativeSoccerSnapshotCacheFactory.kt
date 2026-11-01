package com.alexdiab.stadiatv.sports

fun NativeSoccerSnapshotCache(directory: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeSoccerSnapshotCache(com.alexdiab.stadiatv.foundation.SportsFile(directory.path), now)
