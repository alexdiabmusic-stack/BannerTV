package com.alexdiab.stadiatv.sports

/** Keep existing Android cache directories and disk format. */
fun NativeBasketballSnapshotCache(directory: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeBasketballSnapshotCache(com.alexdiab.stadiatv.foundation.SportsFile(directory.path), now)
