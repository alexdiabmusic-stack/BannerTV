package com.alexdiab.stadiatv.sports

fun NativeMlsSnapshotCache(folder: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeMlsSnapshotCache(com.alexdiab.stadiatv.foundation.SportsFile(folder.path), now)
