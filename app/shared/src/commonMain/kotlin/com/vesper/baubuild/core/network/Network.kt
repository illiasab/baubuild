package com.vesper.baubuild.core.network

import com.vesper.baubuild.core.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

data class ApiConfig(
    /** Python API in server/bau, deployed for this project. Override per build/flavor. */
    val baseUrl: String = "https://baubuild.vesperconnect.org/",
    /** When true the app runs entirely on [com.vesper.baubuild.data.fake] data — used until the backend ships. */
    val useFakeBackend: Boolean = true,
)

val AppJson = Json {
    ignoreUnknownKeys = true   // backend can add fields without breaking old app versions
    explicitNulls = false
    coerceInputValues = true   // unknown enum values fall back to defaults instead of crashing
}

/**
 * Shared HTTP client. Retries idempotent requests with exponential backoff so rolling backend deploys
 * (brief 502/503s) are invisible to users. The opaque session token is attached as a Bearer header.
 */
fun createHttpClient(config: ApiConfig, session: SessionStore): HttpClient = HttpClient {
    install(ContentNegotiation) { json(AppJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
    install(HttpRequestRetry) {
        maxRetries = 3
        retryIf { request, response ->
            request.method.value in setOf("GET", "HEAD", "PUT", "DELETE") &&
                response.status.value in setOf(408, 429, 502, 503, 504)
        }
        retryOnExceptionIf { request, _ -> request.method.value in setOf("GET", "HEAD", "PUT", "DELETE") }
        exponentialDelay()
    }
    defaultRequest {
        url(config.baseUrl)
        session.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }
    expectSuccess = true
}
