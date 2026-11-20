package com.vesper.baubuild.data.http.auth

import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.network.ApiConfig
import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.core.network.apiError
import com.vesper.baubuild.core.network.createHttpClient
import com.vesper.baubuild.core.network.jsonBody
import com.vesper.baubuild.core.network.jsonResponse
import com.vesper.baubuild.core.network.tokenJson
import com.vesper.baubuild.core.network.userJson
import com.vesper.baubuild.core.session.AuthTokens
import com.vesper.baubuild.core.session.DeviceInfo
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.repository.AuthOutcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpAuthRepositoryTest {
    private val session = SessionStore(InMemoryTokenStore(), clock = { 5_000L })
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<JsonObject?>()

    private fun repo(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpAuthRepository {
        val engine = MockEngine { req -> requests += req; bodies += req.jsonBody(); handler(req) }
        val client = createHttpClient(ApiConfig(baseUrl = "https://api.example.test/"), session, engine)
        return HttpAuthRepository(client, session, AccountPolicy(), DeviceInfo("macos", "1.2.3"), clock = { 5_000L })
    }

    private fun MockRequestHandleScope.error(status: Int, code: String, extra: String = "") =
        jsonResponse("""{"error":{"code":"$code","message":"m"$extra}}""", HttpStatusCode.fromValue(status))

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.device() = this["device"]!!.jsonObject

    // register

    @Test
    fun registerSendsNameEmailPasswordDeviceWithoutBearer() = runTest {
        session.signedIn("stale", FakeData.sam) // even with a session, public routes carry no token
        val repo = repo { jsonResponse(tokenJson("acc", "ref")) }
        assertIs<AuthOutcome.SignedIn>(repo.register(" Sam Carter ", " sam@stu.bau.edu ", "buildit42"))
        assertEquals("/v1/auth/register", requests.single().url.encodedPath)
        assertEquals("POST", requests.single().method.value)
        assertNull(requests.single().headers[HttpHeaders.Authorization])
        val body = bodies.single()!!
        assertEquals("Sam Carter", body.str("name"))
        assertEquals("sam@stu.bau.edu", body.str("email"))
        assertEquals("buildit42", body.str("password"))
        assertEquals("macos", body.device().str("platform"))
        assertEquals("1.2.3", body.device().str("app_version"))
    }

    @Test
    fun registerWithTokensSignsInDirectly() = runTest {
        val repo = repo { jsonResponse(tokenJson("acc", "ref", expiresIn = 3600)) }
        val result = assertIs<AuthOutcome.SignedIn>(repo.register("Sam Carter", "sam.carter@stu.bau.edu", "buildit42"))

        assertEquals("/v1/auth/register", requests.single().url.encodedPath) // no verify / me round-trip
        assertEquals(AuthTokens("acc", "ref", 5_000L + 3_600_000L), result.session.tokens)
        assertEquals("acc", session.token)
        assertEquals("Sam Carter", assertIs<SessionState.SignedIn>(session.state.value).user.displayName)
    }

    @Test
    fun registerWithoutTokensIsNotCreated() = runTest {
        val repo = repo { respond("", HttpStatusCode.Accepted) }
        assertEquals(AuthOutcome.NotCreated, repo.register("Sam", "sam@stu.bau.edu", "buildit42"))
        assertIs<SessionState.Restoring>(session.state.value) // never signed in
    }

    @Test
    fun registerErrorCodes() = runTest {
        var next = "domain_not_allowed" to 403
        val repo = repo { error(next.second, next.first) }
        assertEquals(AuthOutcome.DomainNotAllowed, repo.register("A", "a@gmail.com", "buildit42"))
        next = "email_taken" to 409
        assertEquals(AuthOutcome.EmailTaken, repo.register("A", "a@stu.bau.edu", "buildit42"))
        next = "account_reserved" to 409
        assertEquals(AuthOutcome.AccountReserved, repo.register("Ilias", "ilias@bau.edu", "buildit42"))
        next = "weak_password" to 422
        assertEquals(AuthOutcome.WeakPassword, repo.register("A", "a@stu.bau.edu", "short"))
        next = "invalid_body" to 400
        assertEquals("invalid_body", assertFailsWith<ApiException> { repo.register("", "", "") }.code)
        assertEquals(5, requests.size) // POSTs are never retried
    }

    @Test
    fun missingUserInTokenResponseIsFetchedWithTheNewToken() = runTest {
        val repo = repo { req ->
            if (req.url.encodedPath == "/v1/me") jsonResponse(userJson(role = "staff", email = "dr.aydin@bau.edu"))
            else jsonResponse("""{"access_token":"acc","refresh_token":"ref","expires_in":60}""")
        }
        val result = assertIs<AuthOutcome.SignedIn>(repo.login("dr.aydin@bau.edu", "teach2024"))
        assertEquals("Bearer acc", requests.last().headers[HttpHeaders.Authorization])
        assertEquals(Role.Staff, result.session.user.role)
    }

    @Test
    fun loginSendsCredentialsAndSignsIn() = runTest {
        val repo = repo { jsonResponse(tokenJson("acc", "ref")) }
        assertIs<AuthOutcome.SignedIn>(repo.login(" sam.carter@stu.bau.edu ", "buildit42"))
        assertEquals("/v1/auth/login", requests.single().url.encodedPath)
        assertNull(requests.single().headers[HttpHeaders.Authorization])
        val body = bodies.single()!!
        assertEquals("sam.carter@stu.bau.edu", body.str("email"))
        assertEquals("buildit42", body.str("password"))
        assertEquals("1.2.3", body.device().str("app_version"))
        assertEquals("acc", session.token)
    }

    @Test
    fun loginErrorCodes() = runTest {
        var next: Triple<Int, String, String> = Triple(401, "invalid_credentials", "")
        var refreshes = 0
        val repo = repo { req ->
            if (req.url.encodedPath == "/v1/auth/refresh") refreshes++
            error(next.first, next.second, next.third)
        }
        assertEquals(AuthOutcome.InvalidCredentials, repo.login("a@stu.bau.edu", "x"))
        next = Triple(403, "domain_not_allowed", "")
        assertEquals(AuthOutcome.DomainNotAllowed, repo.login("a@stu.bau.edu", "x"))
        next = Triple(429, "too_many_attempts", ""","retry_after_s":900""")
        assertEquals(AuthOutcome.TooManyAttempts(900), repo.login("a@stu.bau.edu", "x"))
        next = Triple(500, "internal", "")
        assertEquals(500, assertFailsWith<ApiException> { repo.login("a@stu.bau.edu", "x") }.status)
        assertEquals(0, refreshes) // a 401 from log-in is the answer, not a reason to refresh
    }

    // me / logout

    @Test
    fun meMapsRolesLeniently() = runTest {
        var role = "staff"
        var email = "dr.aydin@bau.edu"
        val repo = repo { jsonResponse(userJson(role = role, email = email)) }
        session.signedIn("acc", FakeData.sam)

        assertEquals(Role.Staff, repo.me().role)
        assertEquals(AccountKind.BauStaff, repo.me().kind)
        assertEquals("Bearer acc", requests.last().headers[HttpHeaders.Authorization])

        role = "admin"; assertEquals(Role.Staff, repo.me().role)
        role = "lead"; email = "sam.carter@stu.bau.edu"
        assertEquals(Role.Lead, repo.me().role)
        assertEquals(AccountKind.BauStudent, repo.me().kind)
        role = "astronaut" // unknown → email domain decides
        assertEquals(AccountKind.BauStudent, repo.me().kind)
        assertEquals(Role.Student, repo.me().role)
    }

    @Test
    fun signOutPostsLogoutWithBearer() = runTest {
        val repo = repo { respond("", HttpStatusCode.NoContent) }
        session.signedIn("acc", FakeData.sam)
        repo.signOut()
        assertEquals("/v1/auth/logout", requests.single().url.encodedPath)
        assertEquals("Bearer acc", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun requestDtosNeverPrintSecrets() {
        val device = DeviceDto("ios", "1")
        listOf(
            RegisterRequestDto("n", "e@stu.bau.edu", "hunter22", device).toString(),
            LoginRequestDto("e@stu.bau.edu", "hunter22", device).toString(),
        ).forEach { text ->
            assertFalse("hunter22" in text, text)
            assertTrue("e@stu.bau.edu" in text)
        }
    }
}
