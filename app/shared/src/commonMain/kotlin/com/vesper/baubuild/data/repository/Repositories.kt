package com.vesper.baubuild.data.repository

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

/** A backend session: the token pair plus the signed-in user. */
data class AuthSession(val tokens: AuthTokens, val user: User) {
    /** Access-token-only session (tests). */
    constructor(opaqueToken: String, user: User) : this(AuthTokens(opaqueToken), user)

    val opaqueToken: String get() = tokens.accessToken
}

// Typed outcomes of the auth calls. Each call returns its own sealed interface; the shared cases below implement
// every interface they can appear in, so `when` stays exhaustive per call. Network failures and unexpected errors
// (e.g. `400 invalid_body`, 5xx) are thrown as exceptions instead.

/** Outcome of [AuthRepository.register]. */
sealed interface RegisterResult

/** Outcome of [AuthRepository.login]. */
sealed interface LoginResult

sealed interface AuthOutcome {
    /** 200 tokens (register and log-in). The repository has already stored the session (`SessionStore.signedIn`). */
    data class SignedIn(val session: AuthSession) : AuthOutcome, RegisterResult, LoginResult

    /** Register answered without tokens (an unexpected reply): the account wasn't created here; try again. */
    data object NotCreated : AuthOutcome, RegisterResult

    /** `403 domain_not_allowed`: only `@stu.bau.edu` / `@bau.edu` may register and use the app. */
    data object DomainNotAllowed : AuthOutcome, RegisterResult, LoginResult

    /** `409 email_taken`: an account already exists for this email. */
    data object EmailTaken : AuthOutcome, RegisterResult

    /** `409 account_reserved`: a seeded account (e.g. a senior engineer) that an admin has to activate. */
    data object AccountReserved : AuthOutcome, RegisterResult

    /** `422 weak_password` (server rule: min 8 chars, ≥1 letter and ≥1 digit). */
    data object WeakPassword : AuthOutcome, RegisterResult

    /** `401 invalid_credentials`: wrong password or unknown email (indistinguishable on purpose). */
    data object InvalidCredentials : AuthOutcome, LoginResult

    /** `429 too_many_attempts` / `too_many_requests`: repeated failed log-ins. [retryAfterSeconds] from `retry_after_s` / `Retry-After`. */
    data class TooManyAttempts(val retryAfterSeconds: Long?) : AuthOutcome, LoginResult
}

/**
 * Backend contract for accounts (plain email + password; BAU BUILD sends no email). Every body carries
 * `device {platform, app_version}`.
 *
 * - `POST /v1/auth/register {name, email, password, device}` → 200 tokens (signs in directly).
 * - `POST /v1/auth/login {email, password, device}` → 200 tokens.
 * - `POST /v1/auth/refresh` rotates the pair (handled by the HTTP client, core/network); `GET /v1/me` (Bearer);
 *   `POST /v1/auth/logout` (Bearer). Forgotten passwords are reset by BAU BUILD support.
 *
 * Whenever tokens come back the implementation stores the session ([com.vesper.baubuild.core.session.SessionStore]
 * `signedIn`) before returning [AuthOutcome.SignedIn], so the app switches to the signed-in shell.
 */
interface AuthRepository {
    suspend fun register(name: String, email: String, password: String): RegisterResult

    suspend fun login(email: String, password: String): LoginResult

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

    /**
     * Saves which acceptance criteria are ticked: `PATCH /v1/tasks/{id}` `{"criteria_done": [0, 2]}` → the full task.
     * Returns null when this repository can't persist ticks (they then stay local to the screen). Throws on failure.
     */
    suspend fun updateTaskCriteria(projectId: String, taskId: String, criteriaDone: Set<Int>): ProjectTask? = null
}
