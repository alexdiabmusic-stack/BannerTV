package com.alexdiab.stadiatv.sports

fun NativeBaseballExtrasCache(directory: java.io.File, now: () -> Long = System::currentTimeMillis) =
    NativeBaseballExtrasCache(com.alexdiab.stadiatv.foundation.SportsFile(directory.path), now)
