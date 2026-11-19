package com.vesper.baubuild.core.prefs

import android.content.Context
import com.vesper.baubuild.core.auth.AuthPlatform

actual fun platformFlagStore(): FlagStore =
    runCatching { SharedPrefsFlagStore(AuthPlatform.context) }.getOrElse { InMemoryFlagStore() }

private class SharedPrefsFlagStore(context: Context) : FlagStore {
    private val prefs = context.getSharedPreferences("baubuild_flags", Context.MODE_PRIVATE)
    override fun get(key: String): Boolean = prefs.getBoolean(key, false)
    override fun set(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}
