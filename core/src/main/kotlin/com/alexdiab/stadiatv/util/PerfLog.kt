package com.alexdiab.stadiatv.util

import android.os.SystemClock
import android.util.Log
import com.alexdiab.stadiatv.core.BuildConfig

/** Debug-build timing of the heavy start-up stages (logcat tag "Perf"), so slowness on a device is measured, not guessed. */
object PerfLog {
    inline fun <T> measure(name: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            Log.i("Perf", "$name took ${SystemClock.elapsedRealtime() - start} ms")
        }
    }
}
