package com.alexdiab.stadiatv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Debug builds only. `adb shell am broadcast -a com.alexdiab.stadiatv.DEBUG_ADD_XTREAM --es host H --es user U --es pass P`
 * runs the same check-and-add the "Stream Login" form does, for emulators that can't type into the form's fields.
 */
class DebugPlaylistReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.alexdiab.stadiatv.DEBUG_SAMPLE") {
            val seconds = intent.getIntExtra("seconds", 15)
            Thread({ sampleStacks(seconds) }, "debug-sampler").start()
            return
        }
        if (intent.action == "com.alexdiab.stadiatv.DEBUG_ZAP_TEST") {
            val container = (context.applicationContext as StadiaTvApplication).container
            val count = intent.getIntExtra("count", 8)
            val seconds = intent.getIntExtra("seconds", 20)
            // Runs for minutes, so it must not hold the broadcast open (a receiver gets ~10 s before it counts as hung).
            container.appScope.launch(kotlinx.coroutines.Dispatchers.Main) { zapTest(context.applicationContext, container, count, seconds) }
            return
        }
        val host = intent.getStringExtra("host").orEmpty()
        val user = intent.getStringExtra("user").orEmpty()
        val pass = intent.getStringExtra("pass").orEmpty()
        val container = (context.applicationContext as StadiaTvApplication).container
        val pending = goAsync()
        container.appScope.launch {
            val store = container.playlists
            store.validateXtream(host, user, pass, "")
                .onSuccess { count ->
                    store.addXtream("Stream Login", host, user, pass, "")
                    Log.i("DebugPlaylist", "added a stream login with $count channels")
                }
                .onFailure { Log.w("DebugPlaylist", "check failed: ${it.message}") }
            pending.finish()
        }
    }

    /**
     * Plays a spread of the lineup's channels one after another, with no UI in the way, and logs how long each took to
     * show a picture and whether it stalled ("ZapTest" in logcat). Metrics come from the same player the app uses.
     */
    private suspend fun zapTest(context: Context, container: AppContainer, count: Int, seconds: Int) {
        val lineup = container.lineup.lineup.value
        val prefs = container.lineup.prefs.value
        val pool = lineup.entries.filter { !it.isFast && it.tier == com.alexdiab.stadiatv.lineup.Tier.Essential && (it.country == "CA" || it.country == "US") && it.hasGuide }
        if (pool.isEmpty()) { Log.w("ZapTest", "no channels yet"); return }
        val step = maxOf(1, pool.size / count)
        val chosen = (0 until count).mapNotNull { pool.getOrNull(it * step) }
        for (entry in chosen) {
            // Play the way the player screen does: the best stream first, and when the provider rejects it, the next copy.
            val order = entry.streamsInOrder(prefs).take(4)
            val began = android.os.SystemClock.elapsedRealtime()
            var report = ""
            var played = false
            for ((attempt, stream) in order.withIndex()) {
                val controller = com.alexdiab.stadiatv.playback.PlaybackController(context, com.alexdiab.stadiatv.playback.BufferProfile.Balanced, ownsAudioFocus = false)
                controller.setMuted(true)
                var failed = false
                controller.onFailure = { failed = true }
                val started = android.os.SystemClock.elapsedRealtime()
                controller.load(stream.channel)
                var ready = -1L
                while (android.os.SystemClock.elapsedRealtime() < started + 25_000 && !failed) {
                    // No surface here, so no frame is drawn; "playing" is the moment a viewer would see a picture.
                    if (controller.player.isPlaying) { ready = android.os.SystemClock.elapsedRealtime() - started; break }
                    kotlinx.coroutines.delay(100)
                }
                if (ready >= 0) {
                    val startup = android.os.SystemClock.elapsedRealtime() - began
                    kotlinx.coroutines.delay(seconds * 1000L)
                    report = "playing after $startup ms (stream ${attempt + 1} of ${order.size}, ${stream.quality.label}); ${controller.debugSummary()}; captions=${controller.ui.value.textTracks.map { it.label }}"
                    played = true
                } else {
                    Log.i("ZapTest", "  ${entry.name}: stream ${attempt + 1} [${stream.quality.label}] ${if (failed) "was rejected" else "never started"} after ${android.os.SystemClock.elapsedRealtime() - started} ms")
                }
                controller.release()
                kotlinx.coroutines.delay(300)
                if (played) break
            }
            Log.i("ZapTest", "${entry.name}: ${if (played) report else "NO STREAM PLAYED of ${order.size} tried"}")
        }
        Log.i("ZapTest", "done")
    }

    /**
     * A poor man's profiler for devices where simpleperf is not allowed: every 40 ms it records where each busy thread
     * is, then logs the most common app frames ("Sampler" in logcat). Idle and parked threads are ignored.
     */
    private fun sampleStacks(seconds: Int) {
        val counts = HashMap<String, Int>()
        val byThread = HashMap<String, Int>()
        var samples = 0
        val end = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
        while (android.os.SystemClock.elapsedRealtime() < end) {
            for ((thread, stack) in Thread.getAllStackTraces()) {
                if (thread.state != Thread.State.RUNNABLE || stack.isEmpty()) continue
                if (thread.name == "debug-sampler") continue
                val top = stack.firstOrNull { it.className.startsWith("com.alexdiab") } ?: stack.first()
                if (stack.first().methodName.let { it == "epollWait" || it == "nativePollOnce" || it == "park" }) continue
                val key = "${top.className.substringAfterLast('.')}.${top.methodName}:${top.lineNumber} <- ${stack.getOrNull(stack.indexOf(top) + 1)?.let { it.className.substringAfterLast('.') + "." + it.methodName } ?: ""}"
                counts[key] = (counts[key] ?: 0) + 1
                byThread[thread.name.substringBefore('-')] = (byThread[thread.name.substringBefore('-')] ?: 0) + 1
                samples++
            }
            Thread.sleep(40)
        }
        Log.i("Sampler", "busy samples $samples; by thread $byThread")
        counts.entries.sortedByDescending { it.value }.take(18).forEach { Log.i("Sampler", "%5d  %s".format(it.value, it.key)) }
    }
}
