package com.alexdiab.stadiatv.sports

fun NativeLaLigaSnapshotCache(folder: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeLaLigaSnapshotCache(com.alexdiab.stadiatv.foundation.SportsFile(folder.path), now)
