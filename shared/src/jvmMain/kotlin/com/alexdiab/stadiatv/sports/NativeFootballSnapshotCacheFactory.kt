package com.alexdiab.stadiatv.sports

/** Keep existing Android cache directories and disk format. */
fun NativeFootballSnapshotCache(directory: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeFootballSnapshotCache(com.alexdiab.stadiatv.foundation.SportsFile(directory.path), now)
