package com.alexdiab.stadiatv.sports

fun NativeMlsUpcomingCache(folder: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeMlsUpcomingCache(com.alexdiab.stadiatv.foundation.SportsFile(folder.path), now)
