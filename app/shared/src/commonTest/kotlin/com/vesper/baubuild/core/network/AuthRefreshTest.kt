package com.vesper.baubuild.core.network

import com.vesper.baubuild.core.session.AuthTokens
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeData
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class AuthRefreshTest {
    private val config = ApiConfig(baseUrl = "https://api.example.test/")
    private var now = 1_000_000L
    private val store = InMemoryTokenStore()
    private val session = SessionStore(store, clock = { now })

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        createHttpClient(config, session, MockEngine { handler(it) })

    private suspend fun signIn(access: String = "old", refresh: String = "r1", expiresAt: Long? = now + 3_600_000) =
        session.signedIn(AuthTokens(access, refresh, expiresAt), FakeData.sam)

    @Test
    fun attachesBearerOnlyForTheBackendHost() = runTest {
        signIn()
        val seen = mutableMapOf<String, String?>()
        val client = client { req -> seen[req.url.host] = req.headers[HttpHeaders.Authorization]; jsonResponse("{}") }
        client.get("v1/projects")
        client.get("https://elsewhere.test/x")
        assertEquals("Bearer old", seen["api.example.test"])
        assertNull(seen["elsewhere.test"])
    }

    @Test
    fun refreshOn401IsSingleFlightAndRetriesEachRequestOnce() = runTest {
        signIn()
        val refreshes = AtomicInt(0)
        val unauthorized = AtomicInt(0)
        val both401 = CompletableDeferred<Unit>()
        val client = client { req ->
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> {
                    refreshes.incrementAndFetch()
                    assertEquals("r1", req.jsonBody()!!["refresh_token"]!!.jsonPrimitive.content)
                    assertNull(req.headers[HttpHeaders.Authorization]) // token-issuing call carries no Bearer
                    both401.await()
                    jsonResponse(tokenJson("new", "r2"))
                }
                else -> if (req.headers[HttpHeaders.Authorization] == "Bearer new") {
                    jsonResponse("""{"path":"${req.url.encodedPath}"}""")
                } else {
                    if (unauthorized.incrementAndFetch() == 2) both401.complete(Unit)
                    apiError(401, "unauthorized")
                }
            }
        }
        val results = listOf("v1/projects", "v1/me/work").map { path -> async { client.get(path).bodyAsText() } }.awaitAll()

        assertEquals(listOf("""{"path":"/v1/projects"}""", """{"path":"/v1/me/work"}"""), results)
        assertEquals(1, refreshes.load())
        assertEquals(AuthTokens("new", "r2", now + 3_600_000), session.tokens)
        assertTrue("r2" in store.read()!!) // rotated pair persisted
        assertIs<SessionState.SignedIn>(session.state.value)
    }

    @Test
    fun retriesNonIdempotentPostWithBodyAfterRefresh() = runTest {
        signIn()
        val bodies = mutableListOf<String?>()
        val client = client { req ->
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> jsonResponse(tokenJson("new", "r2"))
                else -> {
                    bodies += req.jsonBody()?.get("text")?.jsonPrimitive?.content
                    if (req.headers[HttpHeaders.Authorization] == "Bearer new") jsonResponse("{}") else apiError(401, "unauthorized")
                }
            }
        }
        client.post("v1/inquiries") { contentType(ContentType.Application.Json); setBody(mapOf("text" to "hi")) }
        assertEquals(listOf<String?>("hi", "hi"), bodies)
    }

    @Test
    fun refreshReusedSignsOutAndSurfacesThe401() = runTest {
        signIn()
        val client = client { req ->
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> apiError(401, "refresh_reused")
                else -> apiError(401, "unauthorized")
            }
        }
        val error = assertFailsWith<ApiException> { apiCall { client.get("v1/me").body<String>() } }
        assertEquals(401, error.status)
        assertEquals(SessionState.SignedOut, session.state.value)
        assertNull(session.tokens)
        assertNull(store.read())
    }

    @Test
    fun refreshNetworkFailureKeepsTheSession() = runTest {
        signIn()
        val client = client { req ->
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> apiError(500, "internal")
                else -> apiError(401, "unauthorized")
            }
        }
        assertFailsWith<ApiException> { apiCall { client.get("v1/me").body<String>() } }
        assertIs<SessionState.SignedIn>(session.state.value)
        assertEquals("old", session.token)
    }

    @Test
    fun authEndpointsNeverTriggerRefresh() = runTest {
        signIn()
        var refreshes = 0
        val client = client { req ->
            if (req.url.encodedPath == "/v1/auth/refresh") refreshes++
            apiError(401, "unauthorized")
        }
        assertFailsWith<ApiException> { apiCall { client.post("v1/auth/logout") } }
        assertEquals(0, refreshes)
        assertIs<SessionState.SignedIn>(session.state.value)
    }

    @Test
    fun refreshesProactivelyShortlyBeforeExpiry() = runTest {
        signIn(expiresAt = now + 30_000) // inside the 60 s margin
        val auth = mutableListOf<String?>()
        val client = client { req ->
            when (req.url.encodedPath) {
                "/v1/auth/refresh" -> jsonResponse(tokenJson("fresh", "r2"))
                else -> { auth += req.headers[HttpHeaders.Authorization]; jsonResponse("{}") }
            }
        }
        client.get("v1/projects")
        assertEquals(listOf<String?>("Bearer fresh"), auth)
    }

    @Test
    fun lateRefreshAfterSignOutDoesNotResurrectTheSession() = runTest {
        signIn()
        val previous = session.tokens!!
        session.signOut()
        assertEquals(false, session.updateTokens(previous, AuthTokens("x", "y")))
        assertNull(session.tokens)
    }
}
