package com.alexdiab.stadiatv.net

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** JSON tree parsing accepts bare string literals; do not cache a server's HTML/text denial as valid JSON. */
fun parseServiceJson(body: String): JsonElement {
    val trimmed = body.trim()
    val parsed = Json.parseToJsonElement(trimmed)
    if (parsed is JsonPrimitive && trimmed.firstOrNull() != '"' &&
        trimmed !in setOf("true","false","null") && !JSON_NUMBER.matches(trimmed.trimEnd()))
        throw SerializationException("The service returned a non-JSON response")
    return parsed
}
private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
