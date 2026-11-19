package com.vesper.baubuild.core.auth

/**
 * Microsoft identity platform (Entra ID) app registration used for "Sign in with your BAU account".
 *
 * Fill these in from the Entra admin center → App registrations → BAU BUILD:
 *  - [clientId]: "Application (client) ID".
 *  - [tenant]: BAU's "Directory (tenant) ID" (or its verified domain, e.g. `bau.edu`). Use `organizations`
 *    only if accounts from other tenants must reach the "not available yet" screen.
 *  - Under Authentication → "Mobile and desktop applications", add every redirect URI below
 *    (custom-scheme URIs for iOS/Android, and `http://127.0.0.1` for desktop — Entra ignores the port of
 *    loopback redirect URIs). Enable "Allow public client flows"; the app never holds a client secret.
 *
 * The app only obtains an authorization code (PKCE). The Go backend redeems it at
 * `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token` with the same `client_id`,
 * `code_verifier` and `redirect_uri`, validates the ID token, applies the BAU domain rule, and returns
 * its own opaque session token.
 *
 * While [isConfigured] is false the app uses [FakeMicrosoftSignIn] so it stays usable in development.
 */
data class MicrosoftAuthConfig(
    val clientId: String = PLACEHOLDER_CLIENT_ID,
    val tenant: String = PLACEHOLDER_TENANT,
    /** Skips the home-realm discovery page and goes straight to BAU's sign-in. */
    val domainHint: String? = "bau.edu",
    val scopes: List<String> = listOf("openid", "profile", "email"),
    /** ASWebAuthenticationSession callback; its scheme needs no Info.plist URL type. */
    val iosRedirectUri: String = "com.vesper.baubuild://auth",
    /** Must match the intent-filter of `AuthRedirectActivity` in app/androidApp/src/main/AndroidManifest.xml. */
    val androidRedirectUri: String = "com.vesper.baubuild://auth",
    /** Desktop listens on `http://127.0.0.1:<random free port><desktopRedirectPath>`. */
    val desktopRedirectPath: String = "/auth/callback",
    /** How long desktop waits for the browser to come back before giving up. */
    val desktopTimeoutMillis: Long = 5 * 60 * 1000L,
) {
    val isConfigured: Boolean
        get() = clientId != PLACEHOLDER_CLIENT_ID && tenant != PLACEHOLDER_TENANT && clientId.isNotBlank()

    val authorizeEndpoint: String
        get() = "https://login.microsoftonline.com/$tenant/oauth2/v2.0/authorize"

    companion object {
        const val PLACEHOLDER_CLIENT_ID = "00000000-0000-0000-0000-000000000000"
        const val PLACEHOLDER_TENANT = "YOUR-BAU-TENANT-ID"
    }
}
