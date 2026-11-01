package com.alexdiab.stadiatv.addons

import com.alexdiab.stadiatv.foundation.encodeComponent
import com.alexdiab.stadiatv.foundation.safeWebURL
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Public errors are deliberately constant: transport and JSON exceptions can contain secrets. */
class AddonProblem(message: String) : Exception(message)
class ManifestAddress private constructor(internal val value: String) {
    override fun toString() = "[configured add-on]"
    internal fun sibling(path: String): String {
        val address = value.substringBefore('?')
        return address.removeSuffix("manifest.json") + path + value.removePrefix(address)
    }
    fun configurationPage() = sibling("configure")
    companion object {
        fun parse(raw: String): ManifestAddress {
            val s = raw.trim()
            val path = s.substringBefore('?')
            if (s.length > 16384 || s.any { it.isWhitespace() || it.code < 32 || it == '\\' } || '#' in s ||
                !s.startsWith("https://") || !safeWebURL(s) || !path.endsWith("/manifest.json") ||
                path.substringAfter("://").split('/').any { it.replace("%2e", ".", ignoreCase = true) in setOf(".", "..") })
                throw AddonProblem("Enter the configured HTTPS manifest URL ending in /manifest.json.")
            return ManifestAddress(s)
        }
    }
}

data class Catalog(val type: String, val id: String, val name: String, val extras: Set<String>, val required: Set<String>)
class Manifest internal constructor(internal val raw: JsonObject) {
    val id = raw["id"].string()
    val name = raw["name"].string().take(100)
    val catalogs = raw["catalogs"].array.mapNotNull { c ->
        val type = c["type"].string(); val id = c["id"].string()
        val extra = c["extra"].array
        if (type.isBlank() || id.isBlank()) null else Catalog(type, id, c["name"].string(id),
            extra.mapNotNull { it["name"].stringOrNull() }.toSet() + c["extraSupported"].array.mapNotNull { it.stringOrNull() },
            extra.filter { it["isRequired"].bool() }.mapNotNull { it["name"].stringOrNull() }.toSet() + c["extraRequired"].array.mapNotNull { it.stringOrNull() })
    }
    init {
        if (id.isBlank() || name.isBlank() || raw["version"].string().isBlank() || raw["resources"] !is JsonArray || raw["types"] !is JsonArray)
            throw AddonProblem("The server did not return a valid Stremio manifest.")
        if (raw["behaviorHints"]["configurationRequired"].bool()) throw AddonProblem("Finish configuring the add-on on its website first.")
    }
    fun supports(resource: String, type: String, id: String): Boolean =
        raw["resources"].array.any { r ->
            val resourceName = if (r is JsonObject) r["name"].string() else r.string()
            val types = r["types"] ?: raw["types"]
            val prefixes = r["idPrefixes"] ?: raw["idPrefixes"]
            resource == resourceName && types.array.any { it.string() == type } &&
                (if (resource == "catalog") catalogs.any { it.id == id && it.type == type }
                 else prefixes == null || prefixes.array.any { id.startsWith(it.string()) })
        }
}

/** Implementations must use verified TLS, no logging/cookies/redirects and bound decoded bytes while reading. */
fun interface AddonHttp { @Throws(Exception::class) suspend fun get(url: String): String }
class StremioClient(private val http: AddonHttp) {
    private suspend fun fetch(url: String): JsonObject = try {
        val body = http.get(url)
        if (body.length > 4 * 1024 * 1024) throw AddonProblem("The add-on response was too large.")
        Json.parseToJsonElement(body) as? JsonObject ?: throw AddonProblem("The add-on returned an invalid response.")
    } catch (e: CancellationException) { throw e }
      catch (e: AddonProblem) { throw e }
      catch (_: Exception) { throw AddonProblem("Could not read the add-on. Check its address and connection.") }
    @Throws(Exception::class)
    suspend fun connect(address: ManifestAddress) = Manifest(fetch(address.value))

    private suspend fun resource(a: ManifestAddress, m: Manifest, resource: String, type: String, id: String, extra: Map<String,String> = emptyMap()): JsonObject {
        if (!m.supports(resource, type, id) || listOf(type, id).any { it.isBlank() || it in setOf(".", "..") || it.length > 2048 })
            throw AddonProblem("The add-on does not support this item.")
        if (resource == "catalog") {
            val catalog = m.catalogs.first { it.id == id && it.type == type }
            if (!extra.keys.containsAll(catalog.required) || !catalog.extras.containsAll(extra.keys)) throw AddonProblem("This catalog needs different filters.")
        }
        val suffix = if (extra.isEmpty()) "" else "/" + extra.entries.sortedBy { it.key }.joinToString("&") { encodeComponent(it.key) + "=" + encodeComponent(it.value) }
        return fetch(a.sibling(resource + "/" + encodeComponent(type) + "/" + encodeComponent(id) + suffix + ".json"))
    }
    private fun JsonObject.rows(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: throw AddonProblem("The add-on returned an invalid resource.")
    @Throws(Exception::class)
    suspend fun catalog(a: ManifestAddress, m: Manifest, type: String, id: String, extra: Map<String,String> = emptyMap()): List<Title> =
        resource(a,m,"catalog",type,id,extra).rows("metas").mapNotNull(::title)
    @Throws(Exception::class)
    suspend fun metadata(a: ManifestAddress, m: Manifest, type: String, id: String): Title =
        title(resource(a,m,"meta",type,id)["meta"])?.takeIf { it.id == id && it.type == type } ?: throw AddonProblem("No matching metadata was returned.")
    @Throws(Exception::class)
    suspend fun streams(a: ManifestAddress, m: Manifest, type: String, videoId: String): List<Stream> =
        resource(a,m,"stream",type,videoId).rows("streams").mapNotNull { row ->
            val url = row["url"].stringOrNull()?.takeIf { safeWebURL(it) } ?: return@mapNotNull null
            val hints = row["behaviorHints"]["proxyHeaders"]
            // A source requiring response rewriting is not a directly playable HTTPS source.
            if (hints["response"].obj?.isNotEmpty() == true) return@mapNotNull null
            val headers = hints["request"].obj.orEmpty().mapNotNull { (k,v) ->
                val value = v.stringOrNull() ?: return@mapNotNull null
                if (!k.matches(Regex("[A-Za-z0-9-]{1,64}")) || value.any { it.code < 32 } ||
                    k.lowercase() in setOf("host","content-length","connection","transfer-encoding")) return@mapNotNull null
                k to value
            }.toMap()
            Stream(row["name"].string("Stream").take(120), url, headers)
        }
    @Throws(Exception::class)
    suspend fun subtitles(a: ManifestAddress, m: Manifest, type: String, videoId: String, extra: Map<String,String> = emptyMap()): List<Subtitle> =
        resource(a,m,"subtitles",type,videoId,extra).rows("subtitles").mapNotNull { row ->
            val url = row["url"].stringOrNull()?.takeIf { safeWebURL(it) } ?: return@mapNotNull null
            Subtitle(row["lang"].string("und"), url)
        }
    private fun title(row: JsonElement?): Title? {
        val id = row["id"].stringOrNull() ?: return null
        val type = row["type"].stringOrNull() ?: return null
        return Title(id,type,row["name"].stringOrNull() ?: return null,
            row["poster"].stringOrNull()?.takeIf { safeWebURL(it) }, row["description"].string(),
            row["videos"].array.mapNotNull { v -> Video(v["id"].stringOrNull() ?: return@mapNotNull null,
                v["title"].string(v["name"].string()), v["season"].string().toIntOrNull(), v["episode"].string().toIntOrNull()) })
    }
}
class Title(val id: String, val type: String, val name: String, val poster: String?, val description: String, val videos: List<Video>)
class Video(val id: String, val name: String, val season: Int?, val episode: Int?)
class Stream(val name: String, val url: String, val headers: Map<String,String>)
class Subtitle(val language: String, val url: String)
