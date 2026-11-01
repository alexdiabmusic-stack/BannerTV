package com.alexdiab.stadiatv.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Defensive JSON access. Sports APIs change shape without warning (string ids that were numbers,
 * missing keys, nulls), so every accessor is total: it returns a default instead of throwing.
 * This mirrors the `Value` wrappers the iOS providers use for the same reason.
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun parseJson(text: String): JsonElement = AppJson.parseToJsonElement(text)

/** `element["a"]["b"]` style traversal that yields `null` instead of throwing at any step. */
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }

operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)?.takeUnless { it is JsonNull }

/** Follows a path of object keys, e.g. `root.path("competitions", "0", "status")`. Numeric segments index arrays. */
fun JsonElement?.path(vararg keys: String): JsonElement? {
    var current = this
    for (key in keys) {
        current = when (current) {
            is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) }
            is JsonObject -> current[key]
            else -> null
        }?.takeUnless { it is JsonNull }
        if (current == null) return null
    }
    return current
}

fun JsonElement?.string(default: String = ""): String = (this as? JsonPrimitive)?.contentOrNull ?: default

fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

fun JsonElement?.int(default: Int = 0): Int = long(default.toLong()).toInt()

fun JsonElement?.long(default: Long = 0L): Long {
    val primitive = this as? JsonPrimitive ?: return default
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: primitive.contentOrNull?.toDoubleOrNull()?.toLong() ?: default
}

fun JsonElement?.double(default: Double = 0.0): Double {
    val primitive = this as? JsonPrimitive ?: return default
    return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull() ?: default
}

fun JsonElement?.bool(default: Boolean = false): Boolean {
    val primitive = this as? JsonPrimitive ?: return default
    return primitive.booleanOrNull ?: when (primitive.contentOrNull?.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> default
    }
}

val JsonElement?.array: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()

val JsonElement?.obj: JsonObject? get() = this as? JsonObject

val JsonElement?.isPresent: Boolean get() = this != null && this !is JsonNull

/** Maps array entries that are objects, skipping everything else. */
inline fun <T> JsonElement?.mapObjects(transform: (JsonObject) -> T?): List<T> =
    array.mapNotNull { (it as? JsonObject)?.let(transform) }

/** First non-blank string among the given candidates. */
fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() } ?: ""
