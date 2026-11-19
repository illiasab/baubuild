package com.vesper.baubuild.core.network

import com.vesper.baubuild.core.session.AuthTokens
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.core.session.SignOutReason
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.util.AttributeKey
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ApiConfig(
    /**
     * BAU BUILD backend. All paths are relative to it (`v1/...`). Production: [DEFAULT_BASE_URL]. For local testing
     * against `server/` a DEBUG override is read once at launch ([debugApiBaseUrlOverride]):
     * - desktop: `-Dbau.apiBaseUrl=http://127.0.0.1:8000/` or env `BAU_API_BASE_URL`;
     * - iOS (Debug builds only): launch argument `-apiBaseUrl http://127.0.0.1:8000/` (Xcode → Edit Scheme → Run →
     *   Arguments Passed On Launch);
     * - Android: none (always production); a local build calls `AppGraph.init(AppGraph(ApiConfig(baseUrl = ...)))`.
     */
    val baseUrl: String = debugApiBaseUrlOverride()?.let(::normalizeBaseUrl) ?: DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.baubuild.vesperconnect.org/"
    }
}

/**
 * Platform DEBUG override of [ApiConfig.baseUrl] (system property / env / launch argument), or null for production.
 * Only http(s) URLs are honoured.
 */
internal expect fun debugApiBaseUrlOverride(): String?

/** Trims, requires an http(s) scheme and ensures the trailing slash relative `v1/...` paths rely on. */
internal fun normalizeBaseUrl(raw: String): String? {
    val url = raw.trim()
    if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length <= "https://".length) return null
    return if (url.endsWith("/")) url else "$url/"
}

val AppJson = Json {
    ignoreUnknownKeys = true   // backend can add fields without breaking old app versions
    explicitNulls = false
    coerceInputValues = true   // unknown enum values fall back to defaults instead of crashing
}

/**
 * Shared HTTP client for the BAU BUILD backend.
 * - Retries idempotent requests with exponential backoff so rolling backend deploys (brief 502/503s) are
 *   invisible to users.
 * - [BauAuth]: attaches `Authorization: Bearer <access>` to requests for [ApiConfig.baseUrl], refreshes the token
 *   pair shortly before expiry and once on a 401 (single-flight), and signs out when the refresh is rejected.
 * [engine] is for tests (Ktor `MockEngine`); production uses the platform default engine.
 */
fun createHttpClient(
    config: ApiConfig,
    session: SessionStore,
    engine: HttpClientEngine? = null,
): HttpClient {
    val block: HttpClientConfig<*>.() -> Unit = {
        install(ContentNegotiation) { json(AppJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
        }
        install(HttpRequestRetry) {
            maxRetries = 3
            retryIf { request, response ->
                request.method.value in IDEMPOTENT &&
                    response.status.value in setOf(408, 429, 502, 503, 504)
            }
            retryOnExceptionIf { request, _ -> request.method.value in IDEMPOTENT }
            exponentialDelay()
        }
        install(BauAuth) {
            this.session = session
            this.baseUrl = config.baseUrl
        }
        defaultRequest { url(config.baseUrl) }
        expectSuccess = true
    }
    return if (engine != null) HttpClient(engine, block) else HttpClient(block)
}

private val IDEMPOTENT = setOf("GET", "HEAD", "PUT", "DELETE")

/** `POST /v1/auth/refresh` request / response (extra fields such as `user` are ignored here). */
@Serializable
internal data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class TokenPairResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

/** Converts a token response to [AuthTokens], computing the absolute expiry from `expires_in` (seconds). */
fun authTokensOf(accessToken: String, refreshToken: String?, expiresInSeconds: Long?, nowMillis: Long): AuthTokens =
    AuthTokens(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMillis = expiresInSeconds?.takeIf { it > 0 }?.let { nowMillis + it * 1000 },
    )

class BauAuthConfig {
    lateinit var session: SessionStore
    var baseUrl: String = ApiConfig.DEFAULT_BASE_URL
    /** Refresh this long before the access token expires. */
    var refreshMarginMillis: Long = 60_000
}

/** How [BauAuth] treats a request path. */
internal enum class AuthRouteKind {
    /** Everything else: Bearer token, proactive refresh, refresh-and-retry once on 401. */
    Protected,
    /** `POST /v1/auth/logout`: Bearer token (it revokes that session), but a 401 never triggers a refresh. */
    BearerNoRefresh,
    /**
     * The public auth flow — `/v1/auth/register`, `verify-email`, `resend-code`, `login`, `password/forgot`,
     * `password/reset`, `refresh` and any future `/v1/auth/…` route: no Bearer token, and a 401 (e.g.
     * `invalid_credentials`) is the answer itself, never a reason to refresh.
     */
    Public,
}

/**
 * Classifies a normalised path ("/v1/auth/login", possibly under a base-URL prefix). Session management
 * (`/v1/auth/sessions…`) is a normal authenticated API.
 */
internal fun authRouteKind(path: String): AuthRouteKind {
    val marker = "/v1/auth/"
    val at = path.indexOf(marker)
    if (at < 0) return AuthRouteKind.Protected
    val route = path.substring(at + marker.length).trimEnd('/')
    return when {
        route == "logout" -> AuthRouteKind.BearerNoRefresh
        route == "sessions" || route.startsWith("sessions/") -> AuthRouteKind.Protected
        else -> AuthRouteKind.Public
    }
}

/**
 * Bearer auth + token rotation for the BAU BUILD backend. See [createHttpClient].
 *
 * On a 401 from any endpoint except the auth flow itself, one refresh runs (a [Mutex] makes it single-flight:
 * concurrent 401s wait for it and reuse its result) and the request is retried once with the new access token.
 * A rejected refresh (401, e.g. `refresh_reused` or `unauthorized`, or 400) signs the session out; a `403
 * domain_not_allowed` (the account's domain is no longer allowed to use the app) signs out with
 * [SignOutReason.DomainNotAllowed] so the auth screens can explain it; a network error or 5xx during refresh keeps
 * the session (zero downtime) and surfaces the original 401. The public auth routes ([authRouteKind]) are left alone.
 */
val BauAuth = createClientPlugin("BauAuth", ::BauAuthConfig) {
    val session = pluginConfig.session
    val apiHost = runCatching { Url(pluginConfig.baseUrl).host }.getOrNull()
    val margin = pluginConfig.refreshMarginMillis
    val mutex = Mutex()
    val httpClient = client

    /** Rotates the pair unless someone else already did. Returns the tokens to use, or null if signed out. */
    suspend fun refresh(seen: AuthTokens, signOutOnReject: Boolean): AuthTokens? = mutex.withLock {
        val current = session.tokens ?: return@withLock null
        if (current != seen) return@withLock current // another caller refreshed while we waited
        val refreshToken = current.refreshToken
        if (refreshToken == null) {
            if (signOutOnReject) session.signOut()
            return@withLock if (signOutOnReject) null else current
        }
        try {
            val response = apiCall {
                httpClient.post("v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken))
                }.body<TokenPairResponse>()
            }
            val next = authTokensOf(response.accessToken, response.refreshToken ?: refreshToken, response.expiresIn, session.now())
            if (session.updateTokens(current, next)) next else session.tokens
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 400) {
                // Reuse detected / expired / revoked: the pair is dead, sign in again.
                if (session.tokens == current) session.signOut()
                null
            } else if (e.status == 403 && e.code == "domain_not_allowed") {
                // Usage is enforced on every refresh, not only at registration.
                if (session.tokens == current) session.signOut(SignOutReason.DomainNotAllowed(session.signedInEmail()))
                null
            } else {
                current // backend hiccup: keep the session
            }
        } catch (e: Exception) {
            current // offline: keep the session
        }
    }

    on(Send) { request: HttpRequestBuilder ->
        // Normalised ("/v1/auth/refresh"), independent of how the relative URL was merged with the base URL.
        val path = "/" + request.url.build().rawSegments.filter { it.isNotEmpty() }.joinToString("/")
        val ours = apiHost != null && request.url.host == apiHost
        // A caller-supplied Authorization header wins; ours (from an earlier attempt, e.g. HttpRequestRetry) is replaced.
        val ownHeader = request.headers.contains(HttpHeaders.Authorization) && !request.attributes.contains(BearerFromSession)
        val kind = authRouteKind(path)
        if (!ours || ownHeader || kind == AuthRouteKind.Public) return@on proceed(request)

        var sent = session.tokens
        val refreshable = kind == AuthRouteKind.Protected
        if (sent != null && refreshable && sent.refreshToken != null && sent.expiresSoon(session.now(), margin)) {
            sent = refresh(sent, signOutOnReject = true)
        }
        if (sent != null) {
            request.headers[HttpHeaders.Authorization] = "Bearer ${sent.accessToken}"
            request.attributes.put(BearerFromSession, Unit)
        } else if (request.attributes.contains(BearerFromSession)) {
            request.headers.remove(HttpHeaders.Authorization)
        }

        val call: HttpClientCall = try {
            proceed(request)
        } catch (e: ResponseException) {
            if (e.response.status != HttpStatusCode.Unauthorized || sent == null || !refreshable) throw e
            return@on retryAfter401(request, sent, ::refresh) ?: throw e
        }
        if (call.response.status == HttpStatusCode.Unauthorized && sent != null && refreshable) {
            return@on retryAfter401(request, sent, ::refresh) ?: call
        }
        call
    }
}

/** Marks requests whose Authorization header was set by [BauAuth] (not by the caller). */
private val BearerFromSession = AttributeKey<Unit>("BauBearerFromSession")

private suspend fun io.ktor.client.plugins.api.Send.Sender.retryAfter401(
    request: HttpRequestBuilder,
    sent: AuthTokens,
    refresh: suspend (AuthTokens, Boolean) -> AuthTokens?,
): HttpClientCall? {
    val next = refresh(sent, true) ?: return null
    if (next == sent) return null // refresh couldn't run (offline): surface the original 401
    request.headers[HttpHeaders.Authorization] = "Bearer ${next.accessToken}"
    return proceed(request)
}
