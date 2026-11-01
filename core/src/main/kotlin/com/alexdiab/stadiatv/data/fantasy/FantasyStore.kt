package com.alexdiab.stadiatv.data.fantasy

import android.content.Context
import com.alexdiab.stadiatv.live.CredentialStore
import com.alexdiab.stadiatv.net.AppJson
import com.alexdiab.stadiatv.net.parseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

data class FantasyState(
    val connection: FantasyConnection? = null,
    val snapshot: FantasySnapshot? = null,
    val isLoading: Boolean = false,
    /** The last refresh failed, so what is on screen is the previous copy. */
    val isStale: Boolean = false,
    val error: String? = null,
) {
    val isConnected: Boolean get() = connection != null
}

/**
 * The connected ESPN Fantasy league. The connection is remembered, the session cookies are kept encrypted,
 * and the last league document is cached on disk so the screen opens with data even before the network answers.
 */
class FantasyStore(
    context: Context,
    private val api: EspnFantasyApi,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
) {
    private val connectionFile = File(context.filesDir, "fantasy-connection.json")
    private val cacheFile = File(context.filesDir, "fantasy-league.json")
    private val refreshLock = Mutex()
    private val _state = MutableStateFlow(FantasyState())
    val state: StateFlow<FantasyState> = _state.asStateFlow()

    init {
        loadSaved()?.let { connection ->
            _state.value = FantasyState(connection = connection, snapshot = readCache(connection))
        }
    }

    /** Verifies the league answers before remembering it, so a typo never leaves a half-connected state. */
    suspend fun connect(connection: FantasyConnection, cookies: EspnCookies?) {
        refreshLock.withLock {
            val root = api.league(connection, cookies)
            // A private-league user can leave the team id blank; their SWID says which team is theirs.
            val teamId = connection.teamId ?: cookies?.let { EspnFantasyParser.ownerTeamId(root, it.swid) }
            val named = connection.copy(teamId = teamId, leagueName = EspnFantasyParser.snapshot(root, connection, 0L).leagueName)
            credentials.saveSecret(secretId(named), cookies?.let { it.espnS2 + "\n" + it.swid }.orEmpty())
            connectionFile.writeText(AppJson.encodeToString(named))
            cacheFile.writeText(root.toString())
            _state.value = FantasyState(connection = named, snapshot = EspnFantasyParser.snapshot(root, named, System.currentTimeMillis()))
        }
    }

    fun refresh() {
        val connection = _state.value.connection ?: return
        scope.launch {
            if (!refreshLock.tryLock()) return@launch
            try {
                _state.update { it.copy(isLoading = true, error = null) }
                val root = api.league(connection, cookies(connection))
                cacheFile.writeText(root.toString())
                val snapshot = EspnFantasyParser.snapshot(root, connection, System.currentTimeMillis())
                _state.update { it.copy(snapshot = snapshot, isLoading = false, isStale = false, error = null) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _state.update { it.copy(isLoading = false, isStale = it.snapshot != null, error = error.message ?: "Couldn't reach ESPN Fantasy.") }
            } finally {
                refreshLock.unlock()
            }
        }
    }

    fun disconnect() {
        _state.value.connection?.let { credentials.remove(secretId(it)) }
        connectionFile.delete()
        cacheFile.delete()
        _state.value = FantasyState()
    }

    private fun cookies(connection: FantasyConnection): EspnCookies? {
        val parts = credentials.loadSecret(secretId(connection))?.split('\n', limit = 2) ?: return null
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) EspnCookies(parts[0], parts[1]) else null
    }

    private fun loadSaved(): FantasyConnection? =
        runCatching { AppJson.decodeFromString<FantasyConnection>(connectionFile.readText()) }.getOrNull()

    private fun readCache(connection: FantasyConnection): FantasySnapshot? = runCatching {
        EspnFantasyParser.snapshot(parseJson(cacheFile.readText()), connection, cacheFile.lastModified())
    }.getOrNull()

    private fun secretId(connection: FantasyConnection) = "fantasy:${connection.key}"
}
