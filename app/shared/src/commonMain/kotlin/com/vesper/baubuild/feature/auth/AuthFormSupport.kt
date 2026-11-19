package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the form ViewModels ask of the auth flow (implemented by [AuthViewModel], which owns the in-auth navigation).
 */
interface AuthNavigator {
    /** Register succeeded or log-in found an unverified account: show Verify Email for [email]. */
    fun openVerifyEmail(email: String, resendAfterSeconds: Long?)

    /** The server answered `domain_not_allowed`: show the Not Allowed explanation for [email]. */
    fun showNotAllowed(email: String)
}

/** The email waiting for its 6-digit code. [id] changes with every new request so the screen restarts cleanly. */
data class PendingVerification(val id: Long, val email: String, val resendAfterSeconds: Long?)

/** Used when the server doesn't say how long to wait before "Resend code". */
internal const val DEFAULT_RESEND_AFTER_SECONDS = 60L

internal const val CODE_LENGTH = 6

internal const val MSG_NETWORK = "Couldn’t reach BAU BUILD. Check your connection and try again."
internal const val MSG_GENERIC = "Something went wrong. Please try again."
internal const val MSG_BAU_ONLY = "Only @stu.bau.edu and @bau.edu emails can sign up."
internal const val MSG_USE_BAU_EMAIL = "Use your @stu.bau.edu or @bau.edu email."
internal const val MSG_INVALID_CREDENTIALS = "Email or password is incorrect."
internal const val MSG_WEAK_PASSWORD = "Choose a stronger password: at least 8 characters with a letter and a number."
internal const val MSG_INVALID_CODE = "That code isn’t right. Check the email and try again."
internal const val MSG_CODE_EXPIRED = "This code has expired. Tap “Resend code” to get a new one."
internal const val MSG_CODE_LOCKED = "Too many tries with this code. Tap “Resend code” to get a new one."

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

/** Keeps only digits, at most [CODE_LENGTH] (pasting "123 456" or "Code: 123456" works). */
internal fun normalizeCode(raw: String): String = raw.filter { it in '0'..'9' }.take(CODE_LENGTH)

/** "Resend code" cooldown: calls [onTick] with the seconds left, now and once per second down to 0. */
internal class Countdown(private val scope: CoroutineScope, private val onTick: (Long) -> Unit) {
    private var job: Job? = null

    fun start(seconds: Long) {
        job?.cancel()
        val total = seconds.coerceAtLeast(0)
        onTick(total)
        if (total == 0L) return
        job = scope.launch {
            var left = total
            while (left > 0) {
                delay(1_000)
                left--
                onTick(left)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/** "0:42" for the resend countdown. */
internal fun formatCountdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val rem = s % 60
    return "${s / 60}:${if (rem < 10) "0$rem" else "$rem"}"
}

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
