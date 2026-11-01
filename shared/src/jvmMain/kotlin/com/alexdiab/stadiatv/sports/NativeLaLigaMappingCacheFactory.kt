package com.alexdiab.stadiatv.sports

fun NativeLaLigaMappingCache(folder: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeLaLigaMappingCache(com.alexdiab.stadiatv.foundation.SportsFile(folder.path), now)
