package com.vesper.baubuild.core.session

/**
 * Secure storage for the backend's opaque session token:
 * iOS → Keychain, Android → AES-GCM key in Android Keystore + SharedPreferences ciphertext
 * (requires `AuthPlatform.init(context)`), desktop → java.util.prefs (TODO: OS keychain).
 */
expect fun platformTokenStore(): TokenStore
