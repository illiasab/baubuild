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

/** What a 6-digit email code is for (`purpose` in `POST /v1/auth/resend-code`). */
enum class CodePurpose(val wire: String) { Verify("verify"), Reset("reset") }

// Typed outcomes of the auth calls. Each call returns its own sealed interface; the shared cases below implement
// every interface they can appear in, so `when` stays exhaustive per call. Network failures and unexpected errors
// (e.g. `400 invalid_body`, 5xx) are thrown as exceptions instead.

/** Outcome of [AuthRepository.register]. */
sealed interface RegisterResult

/** Outcome of [AuthRepository.verifyEmail]. */
sealed interface VerifyEmailResult

/** Outcome of [AuthRepository.login]. */
sealed interface LoginResult

/** Outcome of [AuthRepository.resetPassword]. */
sealed interface ResetPasswordResult

/** Outcome of [AuthRepository.resendCode] and [AuthRepository.forgotPassword]. */
sealed interface CodeRequestResult

sealed interface AuthOutcome {
    /** 200 tokens (register and log-in). The repository has already stored the session (`SessionStore.signedIn`). */
    data class SignedIn(val session: AuthSession) :
        AuthOutcome, RegisterResult, VerifyEmailResult, LoginResult, ResetPasswordResult

    /**
     * Legacy server only (the current one sends no email): register → `202 {verification_required, email,
     * resend_after_s}`; login → `403 email_not_verified`. The app shows Verify Email for [email].
     */
    data class VerificationRequired(val email: String, val resendAfterSeconds: Long?) : AuthOutcome, RegisterResult, LoginResult

    /** `202`: a code was sent (or, for forgot-password, would have been if the account exists — no enumeration). */
    data class CodeSent(val resendAfterSeconds: Long?) : AuthOutcome, CodeRequestResult

    /** `501 not_available`: email codes / password reset by email are off (no SMTP); ask BAU BUILD support. */
    data object NotAvailable : AuthOutcome, CodeRequestResult, ResetPasswordResult

    /** `403 domain_not_allowed`: only `@stu.bau.edu` / `@bau.edu` may register and use the app. */
    data object DomainNotAllowed : AuthOutcome, RegisterResult, LoginResult

    /** `409 email_taken`: a verified account already exists for this email. */
    data object EmailTaken : AuthOutcome, RegisterResult

    /** `422 weak_password` (server rule: min 8 chars, ≥1 letter and ≥1 digit). */
    data object WeakPassword : AuthOutcome, RegisterResult, ResetPasswordResult

    /** `401 invalid_credentials`: wrong password or unknown email (indistinguishable on purpose). */
    data object InvalidCredentials : AuthOutcome, LoginResult

    /** `400 invalid_code`. */
    data object InvalidCode : AuthOutcome, VerifyEmailResult, ResetPasswordResult

    /** `410 code_expired` (codes are valid 15 minutes). */
    data object CodeExpired : AuthOutcome, VerifyEmailResult, ResetPasswordResult

    /**
     * `429 too_many_attempts` (5 wrong codes → a new code is needed; repeated failed log-ins → lockout) or
     * `429 too_many_requests` (code requests). [retryAfterSeconds] from `retry_after_s` / `Retry-After`, if sent.
     */
    data class TooManyAttempts(val retryAfterSeconds: Long?) :
        AuthOutcome, VerifyEmailResult, LoginResult, ResetPasswordResult, CodeRequestResult
}

/**
 * Backend contract for accounts (plain email + password, stored in Postgres; no email is ever sent). Every body
 * carries `device {platform, app_version}` where noted.
 *
 * - `POST /v1/auth/register {name, email, password, device}` → 200 tokens (signs in directly; a legacy server
 *   answered 202 verification required, still handled).
 * - `POST /v1/auth/login {email, password, device}` → 200 tokens.
 * - `POST /v1/auth/verify-email`, `resend-code`, `password/forgot`, `password/reset` → `501 not_available`
 *   (legacy servers: the 6-digit email code flow). Forgotten passwords are reset by BAU BUILD support.
 * - `POST /v1/auth/refresh` rotates the pair (handled by the HTTP client, core/network); `GET /v1/me` (Bearer);
 *   `POST /v1/auth/logout` (Bearer).
 *
 * Whenever tokens come back the implementation stores the session ([com.vesper.baubuild.core.session.SessionStore]
 * `signedIn`) before returning [AuthOutcome.SignedIn], so the app switches to the signed-in shell.
 */
interface AuthRepository {
    suspend fun register(name: String, email: String, password: String): RegisterResult

    suspend fun verifyEmail(email: String, code: String): VerifyEmailResult

    suspend fun resendCode(email: String, purpose: CodePurpose): CodeRequestResult

    suspend fun login(email: String, password: String): LoginResult

    /** [AuthOutcome.NotAvailable] on the current server; legacy: [AuthOutcome.CodeSent] unless rate-limited. */
    suspend fun forgotPassword(email: String): CodeRequestResult

    suspend fun resetPassword(email: String, code: String, newPassword: String): ResetPasswordResult

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
