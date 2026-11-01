package com.alexdiab.stadiatv.data.fantasy

/** Compatibility for existing JVM callers; season rules live in commonMain. */
fun FantasySport.currentSeason(now: java.util.Calendar): Int = currentSeason(now.timeInMillis)
