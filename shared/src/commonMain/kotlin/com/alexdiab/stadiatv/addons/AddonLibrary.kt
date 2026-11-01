package com.alexdiab.stadiatv.addons

import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

/** A single device-local encrypted record. Host must throw on locked/failed storage, not return an empty library. */
interface AddonVault {
    @Throws(Exception::class)
    fun read(): String?
    @Throws(Exception::class)
    fun replace(value: String)
}
data class AddonInfo(val key: String, val name: String, val enabled: Boolean)
class Connection internal constructor(val address: ManifestAddress, val manifest: Manifest)

class AddonLibrary(val client: StremioClient, private val vault: AddonVault) {
    private val lock = Mutex()
    private class Entry(val key: String, val address: ManifestAddress, val manifest: Manifest, val enabled: Boolean) {
        fun info() = AddonInfo(key,manifest.name,enabled)
        fun json() = buildJsonObject { put("key",key); put("url",address.value); put("manifest",manifest.raw); put("enabled",enabled) }
    }
    private fun entries(): List<Entry> = try {
        vault.read()?.let { text -> Json.parseToJsonElement(text).jsonArray.map { row -> Entry(
            row["key"].string(),ManifestAddress.parse(row["url"].string()),Manifest((row["manifest"] as JsonObject)),row["enabled"].bool()) } } ?: emptyList()
    } catch (_: Exception) { throw AddonProblem("The saved add-ons could not be unlocked.") }
    private fun save(entries: List<Entry>) {
        try { vault.replace(JsonArray(entries.map(Entry::json)).toString()) }
        catch (_: Exception) { throw AddonProblem("The add-on could not be saved securely.") }
    }
    @Throws(Exception::class)
    suspend fun list(): List<AddonInfo> = lock.withLock { entries().map(Entry::info) }
    @Throws(Exception::class)
    suspend fun connect(raw: String, replacing: String? = null): AddonInfo {
        val address = ManifestAddress.parse(raw); val manifest = client.connect(address)
        return lock.withLock {
            val all = entries(); val old = replacing?.let { key -> all.singleOrNull { it.key == key } ?: throw AddonProblem("This add-on was removed.") }
            if (all.any { it.address.value == address.value && it.key != replacing }) throw AddonProblem("This add-on is already connected.")
            if (old == null && all.size >= 32) throw AddonProblem("Remove an add-on before connecting another.")
            val entry = Entry(old?.key ?: Uuid.random().toString(),address,manifest,old?.enabled ?: true)
            save(if (old == null) all + entry else all.map { if (it.key == old.key) entry else it })
            entry.info()
        }
    }
    @Throws(Exception::class)
    suspend fun check(key: String): AddonInfo {
        val original = lock.withLock { entries().singleOrNull { it.key == key } ?: throw AddonProblem("This add-on was removed.") }
        val fresh = client.connect(original.address)
        return lock.withLock {
            val all = entries()
            val current = all.singleOrNull { it.key == key && it.address.value == original.address.value } ?: throw AddonProblem("This add-on changed.")
            val next = Entry(key,current.address,fresh,current.enabled); save(all.map { if (it.key == key) next else it }); next.info()
        }
    }
    @Throws(Exception::class)
    suspend fun enable(key: String, enabled: Boolean) = lock.withLock {
        val all = entries(); require(all.any { it.key == key })
        save(all.map { if (it.key == key) Entry(key,it.address,it.manifest,enabled) else it })
    }
    @Throws(Exception::class)
    suspend fun remove(key: String) = lock.withLock { save(entries().filterNot { it.key == key }) }
    @Throws(Exception::class)
    suspend fun configurationPage(key: String): String = lock.withLock {
        entries().singleOrNull { it.key == key }?.address?.configurationPage() ?: throw AddonProblem("This add-on was removed.")
    }
    @Throws(Exception::class)
    suspend fun connection(key: String): Connection = lock.withLock {
        entries().singleOrNull { it.key == key && it.enabled }?.let { Connection(it.address,it.manifest) }
            ?: throw AddonProblem("This add-on is disabled or was removed.")
    }
}
