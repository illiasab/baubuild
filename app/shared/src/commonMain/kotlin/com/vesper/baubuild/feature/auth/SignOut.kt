package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.repository.AuthRepository
import com.vesper.baubuild.di.AppGraph
import kotlinx.coroutines.CancellationException

/**
 * Signs out everywhere: best-effort revoke of the opaque token on the backend, then local sign-out.
 * Local sign-out always happens, even offline. Call from a ViewModel scope (e.g. the Profile tab).
 */
suspend fun signOut(
    session: SessionStore = AppGraph.current.session,
    repository: AuthRepository = AppGraph.current.authRepository,
) {
    session.token?.let { token ->
        try {
            repository.signOut(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Server-side revoke failed (offline?). The token is still dropped locally below.
        }
    }
    session.signOut()
}
