package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.network.ApiConfig
import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.core.network.createHttpClient
import com.vesper.baubuild.core.session.AuthTokens
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.repository.MentorCitation
import com.vesper.baubuild.data.repository.MentorEvent
import com.vesper.baubuild.data.repository.MentorRole
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpMentorRepositoryTest {
    private val session = SessionStore(InMemoryTokenStore())
    private val requests = mutableListOf<Pair<HttpRequestData, String?>>()

    private suspend fun repo(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpMentorRepository {
        session.signedIn(AuthTokens("acc", "ref", null), FakeData.sam)
        val engine = MockEngine { req ->
            requests += req to ((req.body as? TextContent)?.text ?: (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString())
            handler(req)
        }
        return HttpMentorRepository(createHttpClient(ApiConfig(baseUrl = "https://api.example.test/"), session, engine))
    }

    private val sse = headersOf(HttpHeaders.ContentType, "text/event-stream")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** Exactly what server/go `mentorStream` writes. */
    private val serverStream = """
        event: status
        data: {"label":"Checking BAU standards"}

        event: citation
        data: {"document_id":"","rule_id":"BAU-SEC-01","title":"Validate input"}

        event: delta
        data: {"text":"Look at "}

        event: delta
        data: {"text":"`validate()`."}

        event: done
        data: {"id":"m9","message_id":"m9","text":"Look at `validate()`."}


    """.trimIndent()

    @Test fun streamsDeltasThroughTheAuthenticatedClient() = runTest {
        val repo = repo { respond(ByteReadChannel(serverStream), HttpStatusCode.OK, sse) }
        val events = repo.send("p 1", "How do I start?", taskId = "t1").toList()
        assertEquals(
            listOf(
                MentorEvent.Status("Checking BAU standards"),
                MentorEvent.Citation(MentorCitation("Validate input", ruleId = "BAU-SEC-01")),
                MentorEvent.Delta("Look at "),
                MentorEvent.Delta("`validate()`."),
                MentorEvent.Done("m9", "Look at `validate()`."),
            ),
            events,
        )
        val (req, body) = requests.single()
        assertEquals("POST", req.method.value)
        assertEquals("/v1/projects/p%201/mentor/messages", req.url.encodedPath)
        assertEquals("Bearer acc", req.headers[HttpHeaders.Authorization])
        assertEquals("text/event-stream", req.headers[HttpHeaders.Accept])
        assertEquals("""{"text":"How do I start?","task_id":"t1"}""", body)
    }

    @Test fun parsesChunksSplitMidLineAndCrlf() = runTest {
        val channel = ByteChannel()
        val repo = repo { respond(channel, HttpStatusCode.OK, sse) }
        launch {
            for (piece in listOf("event: del", "ta\r\ndata: {\"te", "xt\":\"Hi\"}\r\n\r\n: keep-alive\n\n", "event: done\ndata: {}")) {
                channel.writeStringUtf8(piece)
                channel.flush()
            }
            channel.close()
        }
        val events = repo.send("p", "q").toList()
        assertEquals(listOf(MentorEvent.Delta("Hi"), MentorEvent.Done(null, null)), events)
        assertEquals("""{"text":"q"}""", requests.single().second, "no task_id when there is no task")
    }

    @Test fun errorEventAndUnknownEventsAreLenient() = runTest {
        val repo = repo {
            respond(
                ByteReadChannel("event: future\ndata: {\"x\":1}\n\nevent: delta\ndata: not json\n\nevent: error\ndata: {\"code\":\"provider_down\",\"message\":\"Try later\"}\n\n"),
                HttpStatusCode.OK, sse,
            )
        }
        assertEquals(
            listOf(MentorEvent.Delta("not json"), MentorEvent.Error("provider_down", "Try later")),
            repo.send("p", "q").toList(),
        )
    }

    @Test fun plainJsonReplyBecomesDeltaAndDone() = runTest {
        val repo = repo { respond("""{"id":"m1","text":"Hello"}""", HttpStatusCode.OK, json) }
        assertEquals(listOf(MentorEvent.Delta("Hello"), MentorEvent.Done("m1", "Hello")), repo.send("p", "q").toList())
    }

    @Test fun quotaExceededIsAnApiExceptionWithRetryAfter() = runTest {
        val repo = repo {
            respond(
                """{"error":{"code":"quota_exceeded","message":"The mentor limit for today is 60 messages.","retry_after_s":3600}}""",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("3600")),
            )
        }
        val e = assertFailsWith<ApiException> { repo.send("p", "q").toList() }
        assertEquals(429, e.status)
        assertEquals("quota_exceeded", e.code)
        assertEquals("The mentor limit for today is 60 messages.", e.message)
        assertEquals(3600L, e.retryAfterSeconds)
        assertEquals(1, requests.size, "POST is never retried")
    }

    @Test fun badRequestIsAnApiException() = runTest {
        val repo = repo {
            respond("""{"error":{"code":"invalid_body","message":"Ask a question of 1 to 4000 characters."}}""", HttpStatusCode.BadRequest, json)
        }
        val e = assertFailsWith<ApiException> { repo.send("p", "q").toList() }
        assertEquals("invalid_body", e.code)
    }

    @Test fun historyMapsRolesAndCitations() = runTest {
        val repo = repo {
            respond(
                """{"items":[
                  {"id":"a","role":"user","text":"Hi","task_id":"","citations":[],"created_at":"2026-11-19T10:00:00Z"},
                  {"id":"b","role":"assistant","text":"Hello","task_id":"t1","citations":[{"title":"Validate input","rule_id":"BAU-SEC-01","document_id":""}],"created_at":"2026-11-19T10:00:00Z","model":"x"},
                  {"id":"c","role":"student","body":"legacy"},
                  {"id":"d","role":"mentor"}
                ],"next_cursor":null}""",
                HttpStatusCode.OK, json,
            )
        }
        val history = repo.history("p")
        assertEquals(listOf("a", "b", "c"), history.map { it.id }, "rows without text are skipped")
        assertEquals(listOf(MentorRole.User, MentorRole.Assistant, MentorRole.User), history.map { it.role })
        assertNull(history[0].taskId)
        assertEquals("t1", history[1].taskId)
        assertEquals("BAU-SEC-01", history[1].citations.single().label)
        assertEquals("legacy", history[2].text)
        assertTrue(requests.single().first.url.encodedPath.endsWith("/v1/projects/p/mentor/messages"))
    }
}
