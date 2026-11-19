package com.vesper.baubuild.ios

import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Swift-friendly view of [SessionState]. Exposed as plain booleans so SwiftUI can switch on it
 * without SKIE / KMP-NativeCoroutines.
 */
class SessionSnapshot internal constructor(
    val isRestoring: Boolean,
    val isSignedIn: Boolean,
    val userDisplayName: String?,
) {
    val isSignedOut: Boolean get() = !isRestoring && !isSignedIn
}

/** Handle returned by [observeSession]; call [close] to stop receiving updates. */
interface SessionObservation {
    fun close()
}

private fun SessionState.toSnapshot() = when (this) {
    SessionState.Restoring -> SessionSnapshot(isRestoring = true, isSignedIn = false, userDisplayName = null)
    SessionState.SignedOut -> SessionSnapshot(isRestoring = false, isSignedIn = false, userDisplayName = null)
    is SessionState.SignedIn -> SessionSnapshot(isRestoring = false, isSignedIn = true, userDisplayName = user.displayName)
}

/**
 * Collects [com.vesper.baubuild.core.session.SessionStore.state] on the main thread and calls [onChange]
 * with the current value immediately, then on every change. Swift: `SessionBridgeKt.observeSession { snap in … }`.
 */
fun observeSession(onChange: (SessionSnapshot) -> Unit): SessionObservation {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    scope.launch {
        AppGraph.current.session.state.collect { onChange(it.toSnapshot()) }
    }
    return object : SessionObservation {
        override fun close() = scope.cancel()
    }
}

private var started = false

/**
 * Restores the stored session once per process (idempotent). Call at app launch, after any platform
 * [AppGraph.init] (e.g. Keychain token store) has run.
 */
fun startSession() {
    if (started) return
    started = true
    val graph = AppGraph.current
    MainScope().launch { graph.session.restore { graph.authRepository.me(it) } }
}
