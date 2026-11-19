package com.vesper.baubuild.data.http.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * `GET /v1/config` (public):
 * `{"min_supported": {"ios": "1.0.0", ...}, "features": {"mentor": true, ...}, "maintenance": {"message": "..."} | null}`.
 * Parsed by hand from a [JsonObject] so one odd value (wrong type, unknown key) never discards the rest.
 */
data class AppConfig(
    /** Minimum supported app version per platform (`ios`, `macos`, `android`, `windows`, `linux`). */
    val minSupported: Map<String, String> = emptyMap(),
    val features: FeatureFlags = FeatureFlags(),
    /** Non-blocking banner text while the backend is in maintenance; null = no banner. */
    val maintenanceMessage: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Returns null when [body] isn't a JSON object at all (caller fails open). */
        fun parse(body: String): AppConfig? {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            return fromJson(root)
        }

        fun fromJson(root: JsonObject): AppConfig {
            val minSupported = (root["min_supported"] as? JsonObject).orEmpty()
                .mapNotNull { (platform, value) -> value.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { platform.lowercase() to it } }
                .toMap()
            val flags = (root["features"] as? JsonObject).orEmpty()
                .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.booleanOrNull?.let { name to it } }
                .toMap()
            val maintenance = when (val m = root["maintenance"]) {
                is JsonObject -> m["message"]?.stringOrNull()
                is JsonPrimitive -> m.stringOrNull() // tolerate a bare string
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
            return AppConfig(minSupported, FeatureFlags(flags), maintenance)
        }

        private fun JsonElement.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    }
}

/**
 * Feature flags from `GET /v1/config`. Unknown or missing flags use [DEFAULTS] (fail open: features that work
 * without the backend's say-so stay on; paid features stay off).
 */
data class FeatureFlags(val values: Map<String, Boolean> = emptyMap()) {
    operator fun get(name: String): Boolean = values[name] ?: DEFAULTS[name] ?: false

    val billing: Boolean get() = this["billing"]
    val mentor: Boolean get() = this["mentor"]
    val github: Boolean get() = this["github"]
    val push: Boolean get() = this["push"]

    companion object {
        val DEFAULTS = mapOf("billing" to false, "mentor" to true, "github" to true, "push" to true)
    }
}

/** What the UI needs: whether to block for an update, the banner, and the flags. */
data class AppStatus(
    val updateRequired: Boolean = false,
    /** The minimum version that triggered [updateRequired]. */
    val minimumVersion: String? = null,
    val currentVersion: String? = null,
    val maintenanceMessage: String? = null,
    val features: FeatureFlags = FeatureFlags(),
) {
    companion object {
        /** Before the first fetch, or when config can't be fetched: never block. */
        val Open = AppStatus()

        fun from(config: AppConfig, platform: String, appVersion: String): AppStatus {
            val minimum = config.minSupported[platform.lowercase()]
            return AppStatus(
                updateRequired = minimum != null && isBelow(appVersion, minimum),
                minimumVersion = minimum,
                currentVersion = appVersion,
                maintenanceMessage = config.maintenanceMessage,
                features = config.features,
            )
        }
    }
}

/**
 * True only when both versions parse and [current] < [minimum]. Anything unparseable → false (fail open).
 * Compares dot-separated numeric parts (`1.2` == `1.2.0`); suffixes like `-beta` or `+42` are ignored.
 */
fun isBelow(current: String, minimum: String): Boolean {
    val a = parseVersion(current) ?: return false
    val b = parseVersion(minimum) ?: return false
    return compareVersions(a, b) < 0
}

internal fun parseVersion(raw: String): List<Int>? {
    val core = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+').substringBefore(' ')
    if (core.isEmpty()) return null
    val parts = core.split('.')
    if (parts.any { it.isEmpty() || it.length > 9 || !it.all(Char::isDigit) }) return null
    return parts.map { it.toInt() }
}

internal fun compareVersions(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}
