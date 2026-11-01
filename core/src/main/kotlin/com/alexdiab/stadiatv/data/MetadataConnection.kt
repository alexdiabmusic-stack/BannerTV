package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.addons.AddonVault
import com.alexdiab.stadiatv.catalog.TmdbRepository
import com.alexdiab.stadiatv.net.SportsHttp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runtime provisioning keeps private metadata credentials out of source, resources and APKs. */
class MetadataConnection(private val vault: AddonVault, private val http: SportsHttp, scope: CoroutineScope) {
    enum class State { Loading, Unconfigured, Ready, Locked }
    private val lock=Mutex()
    @Volatile private var token=""
    private val mutable=MutableStateFlow(State.Loading)
    val state=mutable.asStateFlow()
    val repository=TmdbRepository(http) { token }
    init { scope.launch(Dispatchers.IO) { lock.withLock {
        try { token=vault.read().orEmpty(); mutable.value=if(token.isBlank()) State.Unconfigured else State.Ready }
        catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.value=State.Locked }
    } } }
    suspend fun connect(value: String) = withContext(Dispatchers.IO) { lock.withLock {
        val candidate=value.trim()
        require(candidate.length in 20..4096 && candidate.none { it.code < 32 })
        TmdbRepository(http) { candidate }.artwork()
        vault.replace(candidate)
        token=candidate
        http.invalidate("https://api.themoviedb.org/")
        mutable.value=State.Ready
    } }
}
