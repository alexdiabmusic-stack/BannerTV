package com.alexdiab.stadiatv.sports.f1

import com.alexdiab.stadiatv.net.HttpStatusException
import kotlinx.coroutines.*

/** One retry/stop policy across Android and Apple; only the actual socket is platform-specific. */
abstract class F1SessionTransport : F1Transport {
    protected abstract suspend fun connect(deliver: suspend (F1TransportEvent) -> Unit)
    override suspend fun firstSnapshot(): List<F1Update> {
        var result = emptyList<F1Update>()
        try { connect { if (it is F1HubEvent.Snapshot) { result = it.updates; throw SnapshotComplete() } } }
        catch (_: SnapshotComplete) { }
        return result
    }
    override suspend fun run(deliver: suspend (F1TransportEvent) -> Unit) {
        var attempts = 0
        while (currentCoroutineContext().isActive) {
            deliver(F1Connection(if (attempts == 0) "negotiating" else "reconnecting", attempts))
            try { connect { event -> if (event is F1HubEvent.Snapshot) attempts = 0; deliver(event) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (error is HttpStatusException && error.code in setOf(401,403)) {
                    deliver(F1Connection("failed", attempts, "F1 timing is unavailable (${error.code}).")); return
                }
                attempts = (attempts + 1).coerceAtMost(30)
                deliver(F1Connection("reconnecting", attempts, "Timing connection interrupted."))
                delay(maxOf((1L shl attempts.coerceIn(1,5)).coerceAtMost(30)*1000,
                    (error as? HttpStatusException)?.retryAfterSeconds?.coerceIn(1,86400)?.times(1000) ?: 0))
            }
        }
    }
    private class SnapshotComplete : RuntimeException()
}
