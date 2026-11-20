package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.network.ApiException

/**
 * What the form ViewModels ask of the auth flow (implemented by [AuthViewModel], which owns the in-auth navigation).
 */
interface AuthNavigator {
    /** The server answered `domain_not_allowed`: show the Not Allowed explanation for [email]. */
    fun showNotAllowed(email: String)
}

internal const val MSG_NETWORK = "Couldn’t reach BAU BUILD. Check your connection and try again."
internal const val MSG_GENERIC = "Something went wrong. Please try again."
internal const val MSG_BAU_ONLY = "Only @stu.bau.edu and @bau.edu emails can sign up."
internal const val MSG_USE_BAU_EMAIL = "Use your @stu.bau.edu or @bau.edu email."
internal const val MSG_INVALID_CREDENTIALS = "Email or password is incorrect."
internal const val MSG_ACCOUNT_RESERVED = "This account is reserved. Ask a BAU BUILD admin to activate it."
internal const val MSG_WEAK_PASSWORD = "Choose a stronger password: at least 8 characters with a letter and a number."
internal const val MSG_NOT_CREATED = "Couldn’t create the account. Try again."

/** "Too many attempts. Try again in N min." (rounded up, at least 1 min). */
internal fun tooManyAttemptsMessage(retryAfterSeconds: Long?): String =
    if (retryAfterSeconds == null || retryAfterSeconds <= 0) {
        "Too many attempts. Try again in a few minutes."
    } else {
        "Too many attempts. Try again in ${((retryAfterSeconds + 59) / 60).coerceAtLeast(1)} min."
    }

/** Inline message for an unexpected failure (network down, API unreachable, server error). */
internal fun failureMessage(e: Throwable): String = when (e) {
    is ApiException -> when {
        e.status in UNREACHABLE_STATUSES -> MSG_NETWORK
        e.code == "invalid_body" -> "Check the details and try again."
        else -> MSG_GENERIC
    }
    else -> MSG_NETWORK
}

/** 404 (API not deployed / wrong base URL) and gateway errors mean the API itself isn't reachable. */
private val UNREACHABLE_STATUSES = setOf(404, 502, 503, 504)

/**
 * DEBUG-only: which auth screen to open first (`login`, `signup`, `forgot`), so screenshots can reach them without
 * tapping. Purely visual — no data, no sign-in bypass. iOS: launch argument `-authScreen login` (Debug binaries only);
 * desktop: `-Dbau.authScreen=login`; Android: never.
 */
internal expect fun debugInitialAuthScreenName(): String?

internal fun debugInitialAuthScreen(): AuthScreen? = when (debugInitialAuthScreenName()?.trim()?.lowercase()) {
    "login" -> AuthScreen.Login
    "signup" -> AuthScreen.SignUp
    "forgot" -> AuthScreen.ForgotPassword
    else -> null
}
