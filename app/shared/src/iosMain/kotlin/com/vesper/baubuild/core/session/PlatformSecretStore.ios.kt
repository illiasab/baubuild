package com.vesper.baubuild.core.session

actual fun platformSecretStore(name: String): TokenStore =
    KeychainTokenStore(service = "com.vesper.baubuild.$name", account = "token")
