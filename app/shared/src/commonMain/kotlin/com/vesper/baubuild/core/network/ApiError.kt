package com.vesper.baubuild.core.network

import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Error from the BAU BUILD API. The server always answers errors as `{"error":{"code":"...","message":"..."}}`
 * (server/go). [code] is stable and machine-readable (e.g. `domain_not_allowed`,
 * `refresh_reused`, `microsoft_not_configured`); [message] is human-readable.
 */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

@Serializable
private data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
private data class ErrorBody(val code: String = "unknown", val message: String = "")

private val errorJson = Json { ignoreUnknownKeys = true }

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
    val body = runCatching { errorJson.decodeFromString(ErrorEnvelope.serializer(), text).error }.getOrNull()
    throw ApiException(status, body?.code ?: "http_$status", body?.message?.ifBlank { null } ?: "Request failed ($status).")
}
