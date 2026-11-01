package com.alexdiab.stadiatv.sports

/** Keep existing Android cache directories and disk format. */
fun NativeHockeySnapshotCache(directory: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeHockeySnapshotCache(com.alexdiab.stadiatv.foundation.SportsFile(directory.path), now)
