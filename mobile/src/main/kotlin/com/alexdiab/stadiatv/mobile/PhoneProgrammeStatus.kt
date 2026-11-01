package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.live.Programme

internal enum class PhoneProgrammeStatus(val label: String) {
    OnNow("ON NOW"), Upcoming("UPCOMING"), Ended("ENDED"), Unknown("TIME UNAVAILABLE")
}

/** XMLTV times, not a provider's promotional "Live" title suffix, determine the state. */
internal fun phoneProgrammeStatus(programme: Programme, now: Long): PhoneProgrammeStatus = when {
    programme.endMillis <= programme.startMillis -> PhoneProgrammeStatus.Unknown
    now < programme.startMillis -> PhoneProgrammeStatus.Upcoming
    now < programme.endMillis -> PhoneProgrammeStatus.OnNow
    else -> PhoneProgrammeStatus.Ended
}
