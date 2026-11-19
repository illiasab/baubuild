package com.vesper.baubuild.data.github

import com.vesper.baubuild.core.prefs.InMemoryFlagStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubBackendRegistrationTest {

    private class Call(val method: String, val path: String, val body: String?, val auth: String?)

    private class Harness(var status: HttpStatusCode = HttpStatusCode.OK, var throwIo: Boolean = false) {
        val calls = mutableListOf<Call>()
        val flags = InMemoryFlagStore()
        val engine = MockEngine { req ->
            calls += Call(req.method.value, req.url.encodedPath, (req.body as? TextContent)?.text, req.headers[HttpHeaders.Authorization])
            if (throwIo) throw IllegalStateException("offline")
            respond(
                if (status.value >= 400) """{"error":{"code":"x_${status.value}","message":"nope"}}""" else """{"connected":true}""",
                status, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        // Same shape as core/network createHttpClient: base URL + Bearer session token, expectSuccess.
        val client = HttpClient(engine) {
            expectSuccess = true
            defaultRequest {
                url("https://api.example.test/")
                headers.append(HttpHeaders.Authorization, "Bearer session-token")
            }
        }
        val registration = GitHubBackendRegistration(HttpGitHubTokenLinkApi(client), flags, log = {})
    }

    @Test fun successPutsTokenAndClearsPending() = runTest {
        val h = Harness()
        h.flags.set(GitHubBackendRegistration.KEY_LINK_PENDING, true)
        assertEquals(GitHubLinkResult.Linked, h.registration.register("github_pat_abc", "demo-student"))
        val call = h.calls.single()
        assertEquals("PUT", call.method)
        assertEquals("/v1/integrations/github/token", call.path)
        assertEquals("Bearer session-token", call.auth)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("github_pat_abc", body["token"]!!.jsonPrimitive.content)
        assertEquals("demo-student", body["login"]!!.jsonPrimitive.content)
        assertFalse(h.registration.isLinkPending)
        assertNull(h.registration.note.value)
    }

    @Test fun notDeployedIsSilentButRetriedLater() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
            val h = Harness(status)
            assertEquals(GitHubLinkResult.NotAvailable, h.registration.register("tok", "me"))
            assertNull(h.registration.note.value, "nothing surfaced for $status")
            assertTrue(h.registration.isLinkPending)
        }
    }

    @Test fun serverErrorShowsNoteAndSetsRetryFlag() = runTest {
        val h = Harness(HttpStatusCode.InternalServerError)
        assertEquals(GitHubLinkResult.RetryLater, h.registration.register("tok", "me"))
        assertEquals(GitHubBackendRegistration.LINK_FAILED_NOTE, h.registration.note.value)
        assertTrue(h.registration.isLinkPending)
    }

    @Test fun networkErrorShowsNoteAndSetsRetryFlag() = runTest {
        val h = Harness(throwIo = true)
        assertEquals(GitHubLinkResult.RetryLater, h.registration.register("tok", "me"))
        assertEquals(GitHubBackendRegistration.LINK_FAILED_NOTE, h.registration.note.value)
        assertTrue(h.registration.isLinkPending)
    }

    @Test fun retryPendingRegistersAndClears() = runTest {
        val h = Harness(HttpStatusCode.BadGateway)
        h.registration.register("tok", "me")
        assertTrue(h.registration.isLinkPending)
        h.status = HttpStatusCode.OK
        h.registration.retryPending { "tok" to "me" }
        assertFalse(h.registration.isLinkPending)
        assertNull(h.registration.note.value)
        assertEquals(2, h.calls.count { it.method == "PUT" })
    }

    @Test fun retryWithoutPendingDoesNothing() = runTest {
        val h = Harness()
        h.registration.retryPending { "tok" to "me" }
        assertTrue(h.calls.isEmpty())
    }

    @Test fun retryWithNoStoredTokenDropsPending() = runTest {
        val h = Harness()
        h.flags.set(GitHubBackendRegistration.KEY_LINK_PENDING, true)
        h.registration.retryPending { null }
        assertFalse(h.registration.isLinkPending)
        assertTrue(h.calls.isEmpty())
    }

    @Test fun unregisterDeletesAndClearsPendingLink() = runTest {
        val h = Harness(HttpStatusCode.InternalServerError)
        h.registration.register("tok", "me")
        h.status = HttpStatusCode.OK
        h.registration.unregister()
        assertEquals("DELETE", h.calls.last().method)
        assertEquals("/v1/integrations/github", h.calls.last().path)
        assertFalse(h.registration.isLinkPending)
        assertFalse(h.registration.isUnlinkPending)
        assertNull(h.registration.note.value)
    }

    @Test fun failedUnregisterIsRetriedOnLaunch() = runTest {
        val h = Harness(HttpStatusCode.ServiceUnavailable)
        h.registration.unregister()
        assertTrue(h.registration.isUnlinkPending)
        h.status = HttpStatusCode.OK
        h.registration.retryPending { error("not needed") }
        assertFalse(h.registration.isUnlinkPending)
        assertEquals(2, h.calls.count { it.method == "DELETE" })
    }

    // ---- decorator (in-memory API so everything runs on the test scheduler) ----

    private class RecordingApi(var fail: Boolean = false) : GitHubTokenLinkApi {
        val calls = mutableListOf<String>()
        override suspend fun putToken(token: String, login: String) {
            calls += "PUT $token $login"
            if (fail) throw IllegalStateException("down")
        }
        override suspend fun deleteToken() { calls += "DELETE" }
    }

    @Test fun connectRegistersInBackgroundWithoutBlocking() = runTest {
        val api = RecordingApi(fail = true)
        val repo = BackendLinkedGitHubRepository(
            FakeGitHubRepository(latency = 0L..0L), GitHubBackendRegistration(api, InMemoryFlagStore(), log = {}),
            storedToken = { "unused" }, scope = backgroundScope, retryOnInit = false,
        )
        val account = repo.connect("  github_pat_typed  ")
        assertEquals("demo-student", account.login)
        runCurrent()
        assertEquals(listOf("PUT github_pat_typed demo-student"), api.calls)
        assertEquals(GitHubBackendRegistration.LINK_FAILED_NOTE, repo.backendLinkNote.value)
    }

    @Test fun disconnectUnregisters() = runTest {
        val api = RecordingApi()
        val repo = BackendLinkedGitHubRepository(
            FakeGitHubRepository(latency = 0L..0L), GitHubBackendRegistration(api, InMemoryFlagStore(), log = {}),
            storedToken = { "unused" }, scope = backgroundScope, retryOnInit = false,
        )
        repo.connect("github_pat_typed")
        runCurrent()
        repo.disconnect()
        runCurrent()
        assertNull(repo.account.value)
        assertEquals(listOf("PUT github_pat_typed demo-student", "DELETE"), api.calls)
    }

    @Test fun launchRetryWaitsForAccountThenLinks() = runTest {
        val api = RecordingApi()
        val flags = InMemoryFlagStore().apply { set(GitHubBackendRegistration.KEY_LINK_PENDING, true) }
        val registration = GitHubBackendRegistration(api, flags, log = {})
        val fake = FakeGitHubRepository(latency = 0L..0L)
        BackendLinkedGitHubRepository(fake, registration, storedToken = { "github_pat_stored" }, scope = backgroundScope)
        runCurrent()
        assertTrue(api.calls.isEmpty(), "waits for the stored account to be restored")
        fake.connect("restored") // stands in for the launch-time restore
        runCurrent()
        assertEquals(listOf("PUT github_pat_stored demo-student"), api.calls)
        assertFalse(registration.isLinkPending)
    }

    @Test fun launchRetryKeepsPendingWhenAccountNeverRestores() = runTest {
        val api = RecordingApi()
        val flags = InMemoryFlagStore().apply { set(GitHubBackendRegistration.KEY_LINK_PENDING, true) }
        val registration = GitHubBackendRegistration(api, flags, log = {})
        BackendLinkedGitHubRepository(
            FakeGitHubRepository(latency = 0L..0L), registration, storedToken = { "github_pat_stored" },
            scope = backgroundScope, restoreTimeoutMs = 1_000,
        )
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
        assertTrue(registration.isLinkPending)
    }

    @Test fun noOpRegistrationNeverFails() = runTest {
        val r = GitHubBackendRegistration.noOp()
        assertEquals(GitHubLinkResult.Linked, r.register("tok", "me"))
        r.unregister()
        assertNull(r.note.value)
    }
}
