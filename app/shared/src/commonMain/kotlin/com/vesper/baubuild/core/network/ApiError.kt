package com.vesper.baubuild.core.network

import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Error from the BAU BUILD API. The server always answers errors as `{"error":{"code":"...","message":"..."}}`
 * (server/go). [code] is stable and machine-readable (e.g. `domain_not_allowed`, `invalid_credentials`,
 * `refresh_reused`); [message] is human-readable.
 *
 * Rate-limit / cooldown hints, when the server sends them: [retryAfterSeconds] from `retry_after_s` (inside `error` or
 * at the top level) or the `Retry-After` header; [resendAfterSeconds] from `resend_after_s` (e.g. `email_not_verified`,
 * where the server has just re-sent a code).
 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val retryAfterSeconds: Long? = null,
    val resendAfterSeconds: Long? = null,
) : Exception(message)

@Serializable
private data class ErrorEnvelope(
    val error: ErrorBody? = null,
    @SerialName("retry_after_s") val retryAfterSeconds: Long? = null,
    @SerialName("resend_after_s") val resendAfterSeconds: Long? = null,
)

@Serializable
private data class ErrorBody(
    val code: String = "unknown",
    val message: String = "",
    @SerialName("retry_after_s") val retryAfterSeconds: Long? = null,
    @SerialName("resend_after_s") val resendAfterSeconds: Long? = null,
)

private val errorJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/**
 * Runs an API call and converts Ktor's [ResponseException] (thrown because `expectSuccess = true`) into
 * [ApiException] with the server's error code. Network failures propagate unchanged.
 */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: ResponseException) {
    val status = e.response.status.value
    val text = runCatching { e.response.bodyAsText() }.getOrDefault("")
    val envelope = runCatching { errorJson.decodeFromString(ErrorEnvelope.serializer(), text) }.getOrNull()
    val body = envelope?.error
    val retryHeader = e.response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
    throw ApiException(
        status = status,
        code = body?.code ?: "http_$status",
        message = body?.message?.ifBlank { null } ?: "Request failed ($status).",
        retryAfterSeconds = (body?.retryAfterSeconds ?: envelope?.retryAfterSeconds ?: retryHeader)?.takeIf { it >= 0 },
        resendAfterSeconds = (body?.resendAfterSeconds ?: envelope?.resendAfterSeconds)?.takeIf { it >= 0 },
    )
}
