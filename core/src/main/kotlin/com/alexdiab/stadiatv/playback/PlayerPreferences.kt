package com.alexdiab.stadiatv.playback

import android.content.Context

/** Per-channel player choices that should stick: the aspect mode last used on each channel. */
class PlayerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("stadiatv.player.v1", Context.MODE_PRIVATE)

    fun aspect(channelKey: String): AspectMode =
        prefs.getString("aspect.$channelKey", null)?.let { name -> AspectMode.entries.firstOrNull { it.name == name } } ?: AspectMode.Fit

    fun setAspect(channelKey: String, mode: AspectMode) {
        prefs.edit().apply { if (mode == AspectMode.Fit) remove("aspect.$channelKey") else putString("aspect.$channelKey", mode.name) }.apply()
    }
}
