package com.alexdiab.stadiatv.sports

/** Keep existing Android cache directories and disk format. */
fun NativeBaseballSnapshotCache(directory: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeBaseballSnapshotCache(com.alexdiab.stadiatv.foundation.SportsFile(directory.path), now)
