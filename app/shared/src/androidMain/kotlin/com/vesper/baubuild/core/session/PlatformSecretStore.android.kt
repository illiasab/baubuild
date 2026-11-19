package com.vesper.baubuild.core.session

actual fun platformSecretStore(name: String): TokenStore =
    KeystoreTokenStore(alias = "baubuild_${name}_key", prefsName = "baubuild_secure_$name")
