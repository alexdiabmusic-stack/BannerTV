package com.alexdiab.stadiatv.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class PhoneSnapshot<T>(val value: T? = null, val loading: Boolean = false, val failed: Boolean = false, val checkedAt: Long = 0, val attemptedAt: Long = 0)

/** Small screen-data cache: coalesces requests, bounds concurrency/memory and retains good data on failure. */
class PhoneSnapshotCache<K, T>(
    private val scope: CoroutineScope,
    private val capacity: Int = 8,
    private val ttlMillis: Long = 300_000,
    private val timeoutMillis: Long = 10_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val isComplete: (T) -> Boolean = { true },
    private val load: suspend (K) -> T,
) {
    init { require(capacity > 0 && ttlMillis > 0 && timeoutMillis > 0) }
    private val lock = Any()
    private val gate = Semaphore(2)
    private val jobs = LinkedHashMap<K, Pair<Long, Job>>()
    private var generation = 0L
    private val snapshots = MutableStateFlow<Map<K, PhoneSnapshot<T>>>(emptyMap())
    val state = snapshots.asStateFlow()

    fun refresh(key: K, force: Boolean = false) = synchronized(lock) {
        val old = snapshots.value[key] ?: PhoneSnapshot()
        if (old.loading || !force && old.attemptedAt != 0L && now() - old.attemptedAt < if (old.failed) 60_000 else ttlMillis) return@synchronized
        val values = LinkedHashMap(snapshots.value).apply { remove(key); put(key, old.copy(loading = true, failed = false, attemptedAt = now())) }
        while (values.size > capacity) { val evicted = values.keys.first(); values.remove(evicted); jobs.remove(evicted)?.second?.cancel() }
        snapshots.value = values
        val token = ++generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = gate.withPermit { withTimeout(timeoutMillis) { load(key) } }
                val complete = isComplete(result)
                update(key, token) { it.copy(value = result, loading = false, failed = !complete, checkedAt = if (complete) now() else it.checkedAt) }
            } catch (_: TimeoutCancellationException) { update(key, token) { it.copy(loading = false, failed = true) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { update(key, token) { it.copy(loading = false, failed = true) } }
            finally { synchronized(lock) { if (jobs[key]?.first == token) jobs.remove(key) } }
        }
        jobs[key] = token to job
        job.start()
    }

    private fun update(key: K, token: Long, transform: (PhoneSnapshot<T>) -> PhoneSnapshot<T>) = synchronized(lock) {
        if (jobs[key]?.first == token) snapshots.value[key]?.let { snapshots.value = snapshots.value + (key to transform(it)) }
    }
}
