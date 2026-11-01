package com.alexdiab.stadiatv.net

import com.alexdiab.stadiatv.foundation.SportsIOException
import kotlinx.serialization.json.JsonElement

class HttpStatusException(val code: Int, val retryAfterSeconds: Long? = null, message: String? = null) :
    SportsIOException(message ?: "HTTP $code")
class ResponseTooLargeException(val limit: Long) : SportsIOException("Response exceeds the safe ${limit}-byte limit")

interface SportsHttp {
    suspend fun getString(url: String, headers: Map<String, String> = emptyMap(), ttlMillis: Long = 0,
                          retries: Int = 2, maxResponseBytes: Long? = null): String =
        getBytes(url, headers, retries, maxResponseBytes).decodeToString()

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), ttlMillis: Long = 0,
                        retries: Int = 2, maxResponseBytes: Long? = null): JsonElement
    suspend fun postForm(url: String, form: Map<String,String>, headers: Map<String,String> = emptyMap()): String
    suspend fun postJson(url: String, json: String, headers: Map<String,String> = emptyMap()): String
    fun invalidate(urlPrefix: String? = null)
    suspend fun getBytes(url: String, headers: Map<String,String> = emptyMap(), retries: Int = 1, maxResponseBytes: Long? = null): ByteArray
}
