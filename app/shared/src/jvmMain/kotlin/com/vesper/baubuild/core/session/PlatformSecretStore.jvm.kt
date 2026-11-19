package com.vesper.baubuild.core.session

import java.util.prefs.Preferences

actual fun platformSecretStore(name: String): TokenStore =
    PreferencesTokenStore(node = Preferences.userRoot().node("com/vesper/baubuild/$name"), key = "token")
