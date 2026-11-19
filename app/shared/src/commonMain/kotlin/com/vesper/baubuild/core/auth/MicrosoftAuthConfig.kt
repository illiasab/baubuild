package com.vesper.baubuild.core.auth

/**
 * FILL IN: "Application (client) ID" of the BAU BUILD app registration (Entra admin center → App registrations).
 * Must be the same `client_id` the backend uses to redeem codes (`MICROSOFT_CLIENT_ID` on the server).
 * While it is the placeholder, sign-in shows "Microsoft sign-in isn't set up for this build yet." and never opens
 * a browser.
 */
const val BAU_ENTRA_CLIENT_ID: String = MicrosoftAuthConfig.PLACEHOLDER_CLIENT_ID

/** FILL IN: BAU's "Directory (tenant) ID" (or its verified domain, e.g. `bau.edu`). Same value as the server's. */
const val BAU_ENTRA_TENANT: String = MicrosoftAuthConfig.PLACEHOLDER_TENANT

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
 * The app only obtains an authorization code (PKCE). The backend (`POST /v1/auth/microsoft/exchange`) redeems it at
 * `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token` with the same `client_id`, `code_verifier` and
 * `redirect_uri`, reads the user's email (Microsoft Graph `/me`, hence `User.Read`), applies the BAU domain rule,
 * and returns its own opaque access + refresh tokens.
 *
 * While [isConfigured] is false, [MicrosoftOAuthSignIn] refuses to start ([MicrosoftSignInNotConfigured]) and the
 * auth screens show an inline "isn't set up for this build yet" error. Fill in [BAU_ENTRA_CLIENT_ID] and
 * [BAU_ENTRA_TENANT] above.
 */
data class MicrosoftAuthConfig(
    val clientId: String = BAU_ENTRA_CLIENT_ID,
    val tenant: String = BAU_ENTRA_TENANT,
    /** Skips the home-realm discovery page and goes straight to BAU's sign-in. */
    val domainHint: String? = "bau.edu",
    /** Must cover what the backend asks for when it redeems the code (`openid profile email User.Read`). */
    val scopes: List<String> = listOf("openid", "profile", "email", "User.Read"),
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
        get() = clientId != PLACEHOLDER_CLIENT_ID && tenant != PLACEHOLDER_TENANT && clientId.isNotBlank() && tenant.isNotBlank()

    val authorizeEndpoint: String
        get() = "https://login.microsoftonline.com/$tenant/oauth2/v2.0/authorize"

    companion object {
        const val PLACEHOLDER_CLIENT_ID = "00000000-0000-0000-0000-000000000000"
        const val PLACEHOLDER_TENANT = "YOUR-BAU-TENANT-ID"
    }
}
