package com.alexdiab.stadiatv.mobile

import kotlinx.serialization.Serializable

@Serializable
data class PhoneFantasyDisplay(val showHome: Boolean = true, val showLive: Boolean = true, val showGuide: Boolean = true)

internal fun phoneShowFantasyGuide(display: PhoneFantasyDisplay, spoilerFree: Boolean): Boolean = display.showGuide && !spoilerFree

internal fun phoneNeedsFantasySchedule(display: PhoneFantasyDisplay, spoilerFree: Boolean): Boolean =
    display.showHome || display.showLive || phoneShowFantasyGuide(display, spoilerFree)
