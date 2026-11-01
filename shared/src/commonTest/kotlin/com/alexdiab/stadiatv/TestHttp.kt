package com.alexdiab.stadiatv

import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.JsonElement

internal class TestHttp(val respond: suspend (String) -> JsonElement) : SportsHttp {
    val calls = mutableListOf<String>()
    val headers = mutableListOf<Map<String,String>>()
    override suspend fun getJson(url: String, headers: Map<String,String>, ttlMillis: Long, retries: Int, maxResponseBytes: Long?): JsonElement {
        calls += url; this.headers += headers; return respond(url)
    }
    override suspend fun getBytes(url: String, headers: Map<String,String>, retries: Int, maxResponseBytes: Long?) = error("Unexpected byte request")
    override suspend fun postForm(url: String, form: Map<String,String>, headers: Map<String,String>) = error("Unexpected form request")
    override suspend fun postJson(url: String, json: String, headers: Map<String,String>) = error("Unexpected JSON post")
    override fun invalidate(urlPrefix: String?) = Unit
}
