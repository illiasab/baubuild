package com.vesper.baubuild.core.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.prefs.Preferences

actual fun platformTokenStore(): TokenStore = PreferencesTokenStore()

/**
 * Desktop token storage in the per-user java.util.prefs store (macOS plist, Windows registry HKCU,
 * Linux ~/.java). Only readable by the OS user, but not encrypted.
 *
 * TODO: move to the OS credential vault — macOS Keychain, Windows Credential Manager, Linux Secret Service
 *  (e.g. via java-keyring / JNA) — keeping this as the fallback when no vault is available.
 */
class PreferencesTokenStore(
    private val node: Preferences = Preferences.userRoot().node("com/vesper/baubuild/session"),
    private val key: String = "opaque_token",
) : TokenStore {
    override suspend fun read(): String? = withContext(Dispatchers.IO) { node.get(key, null) }
    override suspend fun write(token: String) = withContext(Dispatchers.IO) { node.put(key, token); node.flush() }
    override suspend fun clear() = withContext(Dispatchers.IO) { node.remove(key); node.flush() }
}
