package com.vesper.baubuild.data.repository

import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.BauClass
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.NewInquiry
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.core.model.TaskStatus
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.model.Workspace
import com.vesper.baubuild.core.session.AuthTokens

/** Result of exchanging a Microsoft identity for a backend session. */
data class AuthSession(val tokens: AuthTokens, val user: User) {
    /** Access-token-only session (tests). */
    constructor(opaqueToken: String, user: User) : this(AuthTokens(opaqueToken), user)

    val opaqueToken: String get() = tokens.accessToken
}

/** Outcome of [AuthRepository.exchange]. Network failures and unexpected errors are thrown instead. */
sealed interface AuthExchangeResult {
    /** HTTP 200 `{access_token, refresh_token, expires_in, user, entitlement}`. */
    data class SignedIn(val session: AuthSession) : AuthExchangeResult

    /**
     * HTTP 403 `domain_not_allowed`: the Microsoft sign-in worked but it is not a BAU account. No session exists.
     * The backend does not echo the address, so [email] may be blank (the caller fills in the login hint).
     * [access] null → the client explains it with [com.vesper.baubuild.core.model.AccountPolicy].
     */
    data class NotAllowed(val email: String, val access: AccountPolicy.Access? = null) : AuthExchangeResult

    /** HTTP 503 `microsoft_not_configured`: sign-in is not switched on server-side yet. Friendly inline error. */
    data object NotConfigured : AuthExchangeResult

    /** HTTP 401 `microsoft_rejected` (code expired / already used / no email): try again. */
    data object Rejected : AuthExchangeResult
}

/**
 * Backend contract for sign-in (Microsoft only; there is no password login):
 *
 * - `POST /v1/auth/microsoft/exchange` `{code, code_verifier, redirect_uri, device: {platform, app_version}}` →
 *   200 `{access_token, refresh_token, expires_in, user, entitlement}`; 403 `domain_not_allowed`;
 *   503 `microsoft_not_configured`. The backend redeems the PKCE code with Microsoft; Microsoft tokens never
 *   reach the client.
 * - `POST /v1/auth/refresh` `{refresh_token}` rotates the pair (handled by the HTTP client, core/network).
 * - `GET /v1/me` (Bearer) → current user.
 * - `POST /v1/auth/logout` (Bearer) → revokes the current session.
 */
interface AuthRepository {
    suspend fun exchange(code: String, codeVerifier: String, redirectUri: String): AuthExchangeResult

    /** Current user for the session's access token. */
    suspend fun me(): User

    /** Best-effort server-side revoke of the current session (the caller always signs out locally). */
    suspend fun signOut()
}

/**
 * Compatibility shim for `session.restore { authRepository.me(it) }`: the token is already current in the
 * session, so it is ignored. Prefer `session.restore { authRepository.me() }`.
 */
suspend fun AuthRepository.me(@Suppress("UNUSED_PARAMETER") token: String): User = me()

interface ProjectRepository {
    suspend fun openProjects(difficulty: Difficulty? = null): List<Project>
    suspend fun project(id: String): Project
    suspend fun projectsForClass(classCode: String): List<Project>
    /** Open projects join immediately; approval-required ones create a pending request. */
    suspend fun join(projectId: String): Membership
}

interface InquiryRepository {
    suspend fun urgentInquiries(): List<Inquiry>
    /** Staff (@bau.edu) only; backend rejects others. */
    suspend fun submit(inquiry: NewInquiry): Inquiry
}

interface ClassRepository {
    suspend fun myClasses(): List<BauClass>
}

interface WorkRepository {
    suspend fun myWork(): List<Membership>
    suspend fun workspace(projectId: String): Workspace
    suspend fun reviews(): List<ReviewStatus>

    /**
     * Moves one of the user's tasks to [status] ("Start Task": ToDo/Backlog → InProgress, "Mark as Done":
     * InProgress → Done) and returns the updated task.
     *
     * Suggested backend endpoint: `PATCH /v1/projects/{id}/tasks/{taskId}` `{"status": "InProgress"}` →
     * 200 with the full task JSON. 403 when the task isn't assigned to the caller, 404 when it doesn't exist,
     * 409 for a transition the backend refuses. Any failure throws; the caller keeps its state and shows an
     * inline error. Idempotent (same status twice is a no-op), so it may be retried.
     */
    suspend fun updateTaskStatus(projectId: String, taskId: String, status: TaskStatus): ProjectTask
}
