package com.vesper.baubuild.core.prefs

/**
 * Tiny persisted boolean flags for UI hints (e.g. "staff tour seen"). Not for secrets — tokens use TokenStore.
 * Platform stores: SharedPreferences (Android), NSUserDefaults (iOS), java.util.prefs (desktop).
 */
interface FlagStore {
    fun get(key: String): Boolean
    fun set(key: String, value: Boolean)
}

/** Process-lifetime store (tests, or a platform store that isn't available yet). */
class InMemoryFlagStore : FlagStore {
    private val values = mutableMapOf<String, Boolean>()
    override fun get(key: String): Boolean = values[key] ?: false
    override fun set(key: String, value: Boolean) { values[key] = value }
}

expect fun platformFlagStore(): FlagStore
