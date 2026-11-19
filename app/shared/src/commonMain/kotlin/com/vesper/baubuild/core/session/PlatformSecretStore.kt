package com.vesper.baubuild.core.session

/**
 * Secure storage for other secrets (e.g. the student's GitHub token), kept apart from the BAU session token.
 * Same backends as [platformTokenStore] — Keychain / Android Keystore / desktop prefs — under a [name]-specific
 * key, service or node, so clearing one never touches the other.
 */
expect fun platformSecretStore(name: String): TokenStore
