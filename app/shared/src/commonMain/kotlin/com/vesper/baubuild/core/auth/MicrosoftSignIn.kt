package com.vesper.baubuild.core.auth

import io.ktor.http.URLBuilder
import io.ktor.http.Url

/**
 * What the app hands to the backend after Microsoft sign-in: an authorization code plus the PKCE verifier
 * and the exact redirect URI used, which the backend needs to redeem the code.
 */
data class MicrosoftAuthorization(val code: String, val codeVerifier: String, val redirectUri: String)

/** The user closed the browser / sheet. Not an error worth showing. (Not a CancellationException on purpose.) */
class MicrosoftSignInCancelled : Exception("Sign-in was cancelled")

/**
 * This build has no Entra ID app registration yet ([MicrosoftAuthConfig.isConfigured] is false). Thrown before any
 * browser opens, so the user sees an inline explanation instead of a Microsoft error page.
 */
class MicrosoftSignInNotConfigured : Exception("Microsoft sign-in isn't set up for this build yet.")

/** Microsoft returned an OAuth error (e.g. `access_denied`, `invalid_request`) or the callback was malformed. */
class MicrosoftSignInException(val error: String, val description: String? = null) :
    Exception(description ?: error)

/** Common entry point. Implemented by [MicrosoftOAuthSignIn]; tests use a fake. */
interface MicrosoftSignIn {
    /**
     * Suspends until the user finishes in the browser. Throws [MicrosoftSignInCancelled] if they back out.
     * [loginHint] pre-fills the Microsoft account picker (OAuth `login_hint`); the user can still change it.
     */
    suspend fun signIn(loginHint: String? = null): MicrosoftAuthorization
}

/** Raw result of the platform browser leg: the full callback URL Microsoft redirected to. */
data class AuthCallback(val redirectUri: String, val callbackUrl: String)

/**
 * Platform browser leg of the OAuth flow:
 * iOS → ASWebAuthenticationSession, Android → Custom Tab + redirect activity, desktop → system browser +
 * loopback HTTP listener. The redirect URI is chosen by the platform (desktop picks a free port), so the
 * caller builds the authorize URL from it.
 */
interface AuthBrowser {
    suspend fun authorize(config: MicrosoftAuthConfig, buildAuthorizeUrl: (redirectUri: String) -> String): AuthCallback
}

expect fun platformAuthBrowser(): AuthBrowser

/** OAuth 2.0 authorization code + PKCE against the Microsoft identity platform v2.0 endpoint. */
class MicrosoftOAuthSignIn(
    private val config: MicrosoftAuthConfig,
    private val browser: AuthBrowser = platformAuthBrowser(),
    private val pkceFactory: () -> Pkce = Pkce::generate,
    private val stateFactory: () -> String = { randomUrlSafeString(16) },
) : MicrosoftSignIn {

    override suspend fun signIn(loginHint: String?): MicrosoftAuthorization {
        if (!config.isConfigured) throw MicrosoftSignInNotConfigured()
        val pkce = pkceFactory()
        val state = stateFactory()
        val callback = browser.authorize(config) { redirectUri -> authorizeUrl(redirectUri, pkce, state, loginHint) }
        val code = parseCallback(callback.callbackUrl, expectedState = state)
        return MicrosoftAuthorization(code, pkce.verifier, callback.redirectUri)
    }

    fun authorizeUrl(redirectUri: String, pkce: Pkce, state: String, loginHint: String? = null): String =
        URLBuilder(config.authorizeEndpoint).apply {
            parameters.append("client_id", config.clientId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_mode", "query")
            parameters.append("scope", config.scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", pkce.challenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("prompt", "select_account")
            config.domainHint?.let { parameters.append("domain_hint", it) }
            loginHint?.trim()?.takeIf { it.isNotEmpty() }?.let { parameters.append("login_hint", it) }
        }.buildString()

    companion object {
        /** Extracts `code` from the redirect, verifying `state` (CSRF) and surfacing OAuth errors. */
        fun parseCallback(callbackUrl: String, expectedState: String): String {
            val params = Url(callbackUrl).parameters
            params["error"]?.let { error ->
                // Microsoft uses access_denied when the user cancels on the consent/account page.
                if (error == "access_denied" && params["error_subcode"] == "cancel") throw MicrosoftSignInCancelled()
                throw MicrosoftSignInException(error, params["error_description"])
            }
            if (params["state"] != expectedState) throw MicrosoftSignInException("state_mismatch", "Sign-in response didn't match this request.")
            return params["code"]?.takeIf { it.isNotBlank() }
                ?: throw MicrosoftSignInException("missing_code", "Microsoft didn't return an authorization code.")
        }
    }
}

