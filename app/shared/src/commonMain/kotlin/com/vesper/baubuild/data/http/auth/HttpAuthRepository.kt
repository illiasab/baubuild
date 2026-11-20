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
import com.vesper.baubuild.data.repository.LoginResult
import com.vesper.baubuild.data.repository.RegisterResult
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
            // 200 tokens: signs in. Anything without tokens means the account wasn't created here.
            val text = client.post("v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequestDto(name.trim(), email.trim(), password, deviceDto))
            }.bodyAsText()
            val tokens = runCatching { AppJson.decodeFromString(TokenResponseDto.serializer(), text) }.getOrNull()
                ?.takeIf { it.accessToken.isNotBlank() }
            if (tokens != null) storeSession(tokens) else AuthOutcome.NotCreated
        },
        error = { e ->
            when (e.code) {
                "domain_not_allowed" -> AuthOutcome.DomainNotAllowed
                "email_taken" -> AuthOutcome.EmailTaken
                "account_reserved" -> AuthOutcome.AccountReserved
                "weak_password" -> AuthOutcome.WeakPassword
                else -> null
            }
        },
    )

    override suspend fun login(email: String, password: String): LoginResult = handle(
        call = { signIn("v1/auth/login", LoginRequestDto(email.trim(), password, deviceDto)) },
        error = { e ->
            when (e.code) {
                "invalid_credentials" -> AuthOutcome.InvalidCredentials
                "domain_not_allowed" -> AuthOutcome.DomainNotAllowed
                else -> tooMany(e)
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

    private fun tooMany(e: ApiException): AuthOutcome.TooManyAttempts? =
        if (e.code == "too_many_attempts" || e.code == "too_many_requests" || e.status == 429) {
            AuthOutcome.TooManyAttempts(e.retryAfterSeconds)
        } else {
            null
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
