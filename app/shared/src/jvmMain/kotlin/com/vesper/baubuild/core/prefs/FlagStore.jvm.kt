package com.vesper.baubuild.core.prefs

import java.util.prefs.Preferences

actual fun platformFlagStore(): FlagStore =
    runCatching { JavaPrefsFlagStore(Preferences.userRoot().node("com/vesper/baubuild/flags")) }.getOrElse { InMemoryFlagStore() }

private class JavaPrefsFlagStore(private val node: Preferences) : FlagStore {
    override fun get(key: String): Boolean = runCatching { node.getBoolean(key, false) }.getOrDefault(false)
    override fun set(key: String, value: Boolean) {
        runCatching { node.putBoolean(key, value); node.flush() }
    }
}
