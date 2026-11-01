package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.addons.AddonVault
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MetadataConnectionTest {
    private class Vault(var value: String? = null): AddonVault {
        var locked=false
        override fun read(): String? { check(!locked); return value }
        override fun replace(value: String) { check(!locked); this.value=value }
    }
    private class Http: SportsHttp {
        var fail=false
        override suspend fun getJson(url: String, headers: Map<String,String>, ttlMillis: Long, retries: Int, maxResponseBytes: Long?): JsonElement {
            check(!fail)
            return parseJson("""{"images":{"secure_base_url":"https://image.tmdb.org/t/p/","poster_sizes":["w500"],"backdrop_sizes":["w780"]}}""")
        }
        override suspend fun getBytes(url: String, headers: Map<String,String>, retries: Int, maxResponseBytes: Long?)=error("Unexpected bytes")
        override suspend fun postForm(url: String, form: Map<String,String>, headers: Map<String,String>)=error("Unexpected form")
        override suspend fun postJson(url: String, json: String, headers: Map<String,String>)=error("Unexpected post")
        override fun invalidate(urlPrefix: String?) {}
    }
    @Test fun failedReplacementNeverOverwritesTheStoredConnection() = runTest {
        val vault=Vault(); val http=Http(); val connection=MetadataConnection(vault,http,backgroundScope)
        connection.connect("fixture-token-that-is-long-enough")
        assertEquals(MetadataConnection.State.Ready,connection.state.value)
        val original=vault.value;http.fail=true
        try { connection.connect("different-fixture-token-value"); fail("Expected failure") } catch (_: IllegalStateException) {}
        assertEquals(original,vault.value);assertEquals(MetadataConnection.State.Ready,connection.state.value)
    }
    @Test fun aLockedVaultCannotBePresentedAsAnEmptyConnection() = runTest {
        val vault=Vault("existing-fixture-token").apply { locked=true }
        val connection=MetadataConnection(vault,Http(),backgroundScope)
        // Storage initialization runs on IO rather than the test scheduler.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.withTimeout(5000) {
            connection.state.first { it!=MetadataConnection.State.Loading }
        }
        }
        assertEquals(MetadataConnection.State.Locked,connection.state.value)
        assertEquals("existing-fixture-token",vault.value)
    }
}
