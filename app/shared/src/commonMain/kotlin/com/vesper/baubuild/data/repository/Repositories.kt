package com.vesper.baubuild.data.repository

import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.BauClass
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.NewInquiry
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.ReviewStatus
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.model.Workspace

/** Result of exchanging a Microsoft identity for a backend session. */
data class AuthSession(val opaqueToken: String, val user: User)

/** Outcome of [AuthRepository.exchangeMicrosoftCode]. */
sealed interface AuthExchangeResult {
    /** HTTP 200 `{"token": "<opaque>", "user": {...}}`. */
    data class SignedIn(val session: AuthSession) : AuthExchangeResult

    /**
     * HTTP 403 `{"error": "account_not_allowed", "email": "...", "access": "not_yet_available" | "requires_payment"}`.
     * The Microsoft sign-in worked but the account is not a BAU account. No session is created.
     * [access] null → the client explains it with [com.vesper.baubuild.core.model.AccountPolicy].
     */
    data class NotAllowed(val email: String, val access: AccountPolicy.Access? = null) : AuthExchangeResult
}

/**
 * Backend contract for sign-in (Go):
 *
 * `POST /v1/auth/microsoft` `{"code", "code_verifier", "redirect_uri"}` → the backend redeems the code at
 * `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token` (public client: `client_id`, `grant_type=
 * authorization_code`, `code`, `code_verifier`, `redirect_uri`; no secret), validates the ID token
 * (issuer/tenant, audience, signature), reads the email (`email` / `preferred_username` / `upn`), applies the
 * BAU domain rule and returns its own opaque session token. Microsoft tokens never reach the client.
 *
 * `GET /v1/me` (Bearer) → current user; 401 means the token is gone.
 * `POST /v1/auth/signout` (Bearer) → revokes the opaque token server-side.
 */
interface AuthRepository {
    suspend fun exchangeMicrosoftCode(code: String, codeVerifier: String, redirectUri: String): AuthExchangeResult
    suspend fun me(token: String): User
    suspend fun signOut(token: String)
}

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
}
