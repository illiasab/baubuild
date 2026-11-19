package com.vesper.baubuild.data.http.auth

import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.core.network.AppJson
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.core.network.authTokensOf
import com.vesper.baubuild.core.session.DeviceInfo
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.core.session.epochMillis
import com.vesper.baubuild.data.repository.AuthOutcome
import com.vesper.baubuild.data.repository.AuthRepository
import com.vesper.baubuild.data.repository.AuthSession
import com.vesper.baubuild.data.repository.CodePurpose
import com.vesper.baubuild.data.repository.CodeRequestResult
import com.vesper.baubuild.data.repository.LoginResult
import com.vesper.baubuild.data.repository.RegisterResult
import com.vesper.baubuild.data.repository.ResetPasswordResult
import com.vesper.baubuild.data.repository.VerifyEmailResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * [AuthRepository] against the BAU BUILD backend. [client] is the shared client from
 * [com.vesper.baubuild.core.network.createHttpClient] (base URL, Bearer token and refresh handled there; the public
 * `/v1/auth/…` routes are sent without a token and never trigger a refresh). Error handling relies only on the stable
 * error `code`s, never on message wording. Whenever tokens come back the session is stored in [session] first.
 */
class HttpAuthRepository(
    private val client: HttpClient,
    private val session: SessionStore,
    private val policy: AccountPolicy = AccountPolicy(),
    private val device: DeviceInfo = DeviceInfo.current,
    private val clock: () -> Long = ::epochMillis,
) : AuthRepository {

    private val deviceDto get() = DeviceDto(device.platform, device.appVersion)

    override suspend fun register(name: String, email: String, password: String): RegisterResult = handle(
        call = {
            // Current server: 200 tokens (signs in). Legacy server: 202 verification required.
            val text = client.post("v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequestDto(name.trim(), email.trim(), password, deviceDto))
            }.bodyAsText()
            val tokens = runCatching { AppJson.decodeFromString(TokenResponseDto.serializer(), text) }.getOrNull()
                ?.takeIf { it.accessToken.isNotBlank() }
            if (tokens != null) {
                storeSession(tokens)
            } else {
                val sent = runCatching { AppJson.decodeFromString(CodeSentDto.serializer(), text) }.getOrDefault(CodeSentDto())
                AuthOutcome.VerificationRequired(sent.email?.takeIf { it.isNotBlank() } ?: email.trim(), sent.resendAfterSeconds)
            }
        },
        error = { e ->
            when (e.code) {
                "domain_not_allowed" -> AuthOutcome.DomainNotAllowed
                "email_taken" -> AuthOutcome.EmailTaken
                "weak_password" -> AuthOutcome.WeakPassword
                else -> null
            }
        },
    )

    override suspend fun verifyEmail(email: String, code: String): VerifyEmailResult = handle(
        call = { signIn("v1/auth/verify-email", VerifyEmailRequestDto(email.trim(), code.trim(), deviceDto)) },
        error = { e ->
            when (e.code) {
                "invalid_code" -> AuthOutcome.InvalidCode
                "code_expired" -> AuthOutcome.CodeExpired
                else -> tooMany(e)
            }
        },
    )

    override suspend fun resendCode(email: String, purpose: CodePurpose): CodeRequestResult = handle(
        call = { AuthOutcome.CodeSent(postForCodeSent("v1/auth/resend-code", ResendCodeRequestDto(email.trim(), purpose.wire)).resendAfterSeconds) },
        error = { e -> notAvailable(e) ?: tooMany(e) },
    )

    override suspend fun login(email: String, password: String): LoginResult = handle(
        call = { signIn("v1/auth/login", LoginRequestDto(email.trim(), password, deviceDto)) },
        error = { e ->
            when (e.code) {
                "invalid_credentials" -> AuthOutcome.InvalidCredentials
                // The server has just re-sent a verification code.
                "email_not_verified" -> AuthOutcome.VerificationRequired(email.trim(), e.resendAfterSeconds)
                "domain_not_allowed" -> AuthOutcome.DomainNotAllowed
                else -> tooMany(e)
            }
        },
    )

    override suspend fun forgotPassword(email: String): CodeRequestResult = handle(
        call = { AuthOutcome.CodeSent(postForCodeSent("v1/auth/password/forgot", ForgotPasswordRequestDto(email.trim())).resendAfterSeconds) },
        error = { e -> notAvailable(e) ?: tooMany(e) },
    )

    override suspend fun resetPassword(email: String, code: String, newPassword: String): ResetPasswordResult = handle(
        call = { signIn("v1/auth/password/reset", ResetPasswordRequestDto(email.trim(), code.trim(), newPassword, deviceDto)) },
        error = { e ->
            when (e.code) {
                "weak_password" -> AuthOutcome.WeakPassword
                "invalid_code" -> AuthOutcome.InvalidCode
                "code_expired" -> AuthOutcome.CodeExpired
                else -> notAvailable(e) ?: tooMany(e)
            }
        },
    )

    override suspend fun me(): User = apiCall { client.get("v1/me").body<AuthUserDto>() }.toUser(policy)

    override suspend fun signOut() {
        apiCall { client.post("v1/auth/logout") }
    }

    /** Runs [call]; maps a known [ApiException] code with [error], rethrowing anything [error] doesn't know. */
    private suspend fun <R> handle(call: suspend () -> R, error: (ApiException) -> R?): R = try {
        apiCall { call() }
    } catch (e: ApiException) {
        error(e) ?: throw e
    }

    /** `501 not_available`: the server sends no email (no codes, no reset by email). */
    private fun notAvailable(e: ApiException): AuthOutcome.NotAvailable? =
        if (e.code == "not_available" || e.status == 501) AuthOutcome.NotAvailable else null

    private fun tooMany(e: ApiException): AuthOutcome.TooManyAttempts? =
        if (e.code == "too_many_attempts" || e.code == "too_many_requests" || e.status == 429) {
            AuthOutcome.TooManyAttempts(e.retryAfterSeconds)
        } else {
            null
        }

    /** POSTs [body] and decodes a `202` leniently (an empty or unexpected body still counts as "code sent"). */
    private suspend inline fun <reified B : Any> postForCodeSent(path: String, body: B): CodeSentDto {
        val text = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        return runCatching { AppJson.decodeFromString(CodeSentDto.serializer(), text) }.getOrDefault(CodeSentDto())
    }

    /** POSTs [body] to a token-issuing route, stores the session and returns it. */
    private suspend inline fun <reified B : Any> signIn(path: String, body: B): AuthOutcome.SignedIn {
        val response = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body<TokenResponseDto>()
        return storeSession(response)
    }

    /** Stores the session from a token response (fetching `/v1/me` if `user` is missing) and returns it. */
    private suspend fun storeSession(response: TokenResponseDto): AuthOutcome.SignedIn {
        val tokens = authTokensOf(response.accessToken, response.refreshToken, response.expiresIn, clock())
        // The session isn't stored yet, so a missing `user` is fetched with the new token explicitly.
        val user = response.user?.toUser(policy)
            ?: client.get("v1/me") { header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}") }.body<AuthUserDto>().toUser(policy)
        session.signedIn(tokens, user)
        return AuthOutcome.SignedIn(AuthSession(tokens, user))
    }
}
