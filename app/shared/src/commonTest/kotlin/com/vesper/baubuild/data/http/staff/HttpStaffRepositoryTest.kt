package com.vesper.baubuild.data.http.staff

import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.InquiryDraft
import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.ProjectFor
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.RewardType
import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.data.http.staff.dto.InquiryDescription
import com.vesper.baubuild.data.http.staff.dto.toStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpStaffRepositoryTest {

    private class Call(val method: String, val path: String, val body: JsonObject?, val auth: String?)

    private class Harness(val handler: MockRequestHandleScope.(method: String, path: String, req: HttpRequestData) -> HttpResponseData) {
        val calls = mutableListOf<Call>()
        val client = HttpClient(MockEngine { req ->
            val body = (req.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
            calls += Call(req.method.value, req.url.encodedPath, body, req.headers[HttpHeaders.Authorization])
            handler(req.method.value, req.url.encodedPath, req)
        }) {
            expectSuccess = true
            defaultRequest {
                url("https://api.baubuild.vesperconnect.org/")
                headers.append(HttpHeaders.Authorization, "Bearer tok")
            }
        }
        val repo = HttpStaffRepository(client)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String, message: String) =
        json("""{"error":{"code":"$code","message":"$message"}}""", status)

    /** Exactly the backend's inquiry payload shape. */
    private fun inquiryJson(
        id: String,
        status: String,
        title: String = "Build a lab equipment booking app",
        description: String = "Students book lab gear on paper.\n\nWhat done looks like:\n- Students see live availability\n- Two bookings can never overlap\n\nProject type: iOS App, Android App\nFor: Class Project · CMPS433\nReward: $40 + SSN",
        bounty: Int = 40,
        urgent: Boolean = false,
        reviewer: String = "professor",
        dueAt: String? = "2026-12-03T23:59:59Z",
    ) = """
        {"id":"$id","title":"$title","description":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(description))},
         "department":"Computer Science","difficulty":"medium","bounty":$bounty,"is_urgent":$urgent,
         "due_at":${dueAt?.let { "\"$it\"" } ?: "null"},"repo_url":null,"min_score":150,"joining":"approval",
         "reviewer_type":"$reviewer","status":"$status","project_id":null,"created_at":"2026-11-18T10:00:00Z",
         "some_future_field":{"x":1}}
    """.trimIndent()

    private val me = """{"id":"u1","name":"Nora Hale","initials":"NH","avatar":null,"bio":"","role":"staff",
        "department":"Computer Science","skills":[],"classes":["CMPS210","CMPS433"],"email":"nora.hale@bau.edu"}"""

    @Test
    fun listMapsBackendInquiries() = runTest {
        val h = Harness { _, path, _ ->
            assertEquals("/v1/inquiries/mine", path)
            json("""{"items":[${inquiryJson("a1", "submitted")},${inquiryJson("a2", "published", reviewer = "ai_then_lead", urgent = true, description = "Plain text only")}]}""")
        }
        val list = h.repo.inquiries()
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("a1", first.id)
        assertEquals(InquiryStatus.Posted, first.status)
        assertEquals("Waiting for review", first.latest)
        assertEquals("Students book lab gear on paper.", first.draft.explanation)
        assertEquals(listOf("Students see live availability", "Two bookings can never overlap"), first.refined!!.doneLooksLike)
        assertEquals(setOf(ProjectType.IosApp, ProjectType.AndroidApp), first.draft.types)
        assertEquals(ProjectFor.ClassProject, first.draft.projectFor)
        assertEquals("CMPS433", first.draft.classCode)
        assertEquals(setOf(RewardType.Price, RewardType.Ssn), first.draft.rewards)
        assertEquals("$40 + SSN", first.rewardLabel)
        assertEquals("2026-12-03", first.draft.deadline)
        assertEquals(Difficulty.Medium, first.draft.difficulty)
        assertEquals(150, first.refined!!.minScore)
        assertEquals("professor", first.reviewedBy)

        val second = list[1]
        assertEquals("Live on the board", second.latest)
        assertEquals(InquiryPriority.Urgent, second.draft.priority)
        assertEquals(ProjectFor.BauProject, second.draft.projectFor)
        assertEquals("Plain text only", second.draft.explanation)
        assertEquals("dev lead", second.reviewedBy)
        assertEquals("Bearer tok", h.calls.single().auth)
    }

    @Test
    fun statusMappingIsLenient() {
        assertEquals(InquiryStatus.Posted, "submitted".toStatus())
        assertEquals(InquiryStatus.Posted, "approved".toStatus())
        assertEquals(InquiryStatus.Posted, "published".toStatus())
        assertEquals(InquiryStatus.Done, "closed".toStatus())
        assertEquals(InquiryStatus.InProgress, "in_progress".toStatus())
        assertEquals(InquiryStatus.Cancelled, "cancelled".toStatus())
        assertEquals(InquiryStatus.Cancelled, "REJECTED".toStatus())
        assertEquals(InquiryStatus.Posted, "something_new".toStatus())
    }

    @Test
    fun detailFetchesOne() = runTest {
        val h = Harness { _, path, _ -> assertEquals("/v1/inquiries/a1", path); json(inquiryJson("a1", "closed", dueAt = null)) }
        val one = h.repo.inquiry("a1")
        assertEquals(InquiryStatus.Done, one.status)
        assertEquals("Delivered", one.latest)
        assertNull(one.draft.deadline)
    }

    @Test
    fun postSendsBackendShapeAndUsesRefinedVersion() = runTest {
        val h = Harness { method, path, _ ->
            when ("$method $path") {
                "GET /v1/me" -> json(me)
                "POST /v1/inquiries" -> json(inquiryJson("new1", "submitted"), HttpStatusCode.Created)
                else -> error(HttpStatusCode.NotFound, "not_found", "No route")
            }
        }
        val draft = InquiryDraft(
            title = "lab booking app", explanation = "students book on paper", expectation = "see what is free. book it",
            types = setOf(ProjectType.IosApp), projectFor = ProjectFor.ClassProject, classCode = "CMPS433",
            rewards = setOf(RewardType.Price, RewardType.Ssn), priceUsd = 40, priority = InquiryPriority.Urgent,
            deadline = "2026-12-03",
        )
        val refined = h.repo.refine(draft)
        assertEquals("Build Lab booking app", refined.title)
        assertTrue(h.calls.isEmpty(), "refine is a client-side fallback")

        val posted = h.repo.post(draft, refined)
        assertEquals("new1", posted.id)
        assertEquals(InquiryStatus.Posted, posted.status)
        val body = h.calls.last { it.method == "POST" }.body!!
        assertEquals("Build Lab booking app", body["title"]!!.jsonPrimitive.content)
        assertEquals("Computer Science", body["department"]!!.jsonPrimitive.content)
        assertEquals(refined.difficulty.name.lowercase(), body["difficulty"]!!.jsonPrimitive.content)
        assertEquals(40, body["bounty"]!!.jsonPrimitive.int)
        assertEquals(true, body["is_urgent"]!!.jsonPrimitive.boolean)
        assertEquals("2026-12-03T23:59:59Z", body["due_at"]!!.jsonPrimitive.content)
        assertEquals(refined.minScore, body["min_score"]!!.jsonPrimitive.int)
        assertEquals("approval", body["joining"]!!.jsonPrimitive.content)
        assertEquals("professor", body["reviewer_type"]!!.jsonPrimitive.content)
        val parsed = InquiryDescription.parse(body["description"]!!.jsonPrimitive.content)
        assertEquals(refined.brief, parsed.body)
        assertEquals(refined.doneLooksLike, parsed.done)
        assertEquals("CMPS433", parsed.classCode)
        assertEquals(setOf(RewardType.Price, RewardType.Ssn), parsed.rewards)
    }

    @Test
    fun patchSendsPartialFieldsAndClearsDeadline() = runTest {
        val h = Harness { method, path, _ ->
            assertEquals("PATCH /v1/inquiries/a1", "$method $path")
            json(inquiryJson("a1", "submitted", title = "New title"))
        }
        val updated = h.repo.updateDetails("a1", InquiryDraft(title = "New title", explanation = "x", expectation = "a\nb", priceUsd = 25))
        assertEquals("New title", updated.title)
        val body = h.calls.single().body!!
        assertEquals("New title", body["title"]!!.jsonPrimitive.content)
        assertEquals(25, body["bounty"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, body["due_at"])
        assertEquals("ai_then_lead", body["reviewer_type"]!!.jsonPrimitive.content)
        assertTrue("difficulty" !in body, "null difficulty = let AI suggest → not sent")
    }

    @Test
    fun cancelDeletesAndReturnsCancelled() = runTest {
        val h = Harness { method, _, _ ->
            when (method) {
                "GET" -> json(inquiryJson("a1", "submitted"))
                "DELETE" -> respond("", HttpStatusCode.NoContent)
                else -> error(HttpStatusCode.MethodNotAllowed, "x", "x")
            }
        }
        val cancelled = h.repo.cancel("a1")
        assertEquals(InquiryStatus.Cancelled, cancelled.status)
        assertEquals(listOf("GET /v1/inquiries/a1", "DELETE /v1/inquiries/a1"), h.calls.map { "${it.method} ${it.path}" })
    }

    @Test
    fun lockedInquiryErrorsBecomeApiException() = runTest {
        val h = Harness { method, _, _ ->
            if (method == "GET") json(inquiryJson("a1", "approved"))
            else error(HttpStatusCode.Conflict, "inquiry_locked", "Only a submitted inquiry can be deleted.")
        }
        val e = assertFailsWith<ApiException> { h.repo.cancel("a1") }
        assertEquals(409, e.status)
        assertEquals("inquiry_locked", e.code)
    }

    @Test
    fun nonStaffGetsRoleRequired() = runTest {
        val h = Harness { _, _, _ -> error(HttpStatusCode.Forbidden, "role_required", "Only staff.") }
        val e = assertFailsWith<ApiException> { h.repo.inquiries() }
        assertEquals("role_required", e.code)
    }

    @Test
    fun draftsStayOnDeviceAndShowFirst() = runTest {
        val h = Harness { _, _, _ -> json("""{"items":[${inquiryJson("a1", "submitted")}]}""") }
        val saved = h.repo.saveDraft(InquiryDraft(title = "idea"))
        assertEquals(InquiryStatus.Draft, saved.status)
        assertTrue(h.calls.isEmpty())
        val list = h.repo.inquiries()
        assertEquals(listOf(saved.id, "a1"), list.map { it.id })
        assertEquals("idea", h.repo.inquiry(saved.id).draft.title)
        h.repo.cancel(saved.id)
        assertEquals(listOf("a1"), h.repo.inquiries().map { it.id })
    }

    @Test
    fun overviewIsComputedFromInquiries() = runTest {
        val h = Harness { _, _, _ ->
            json(
                """{"items":[${inquiryJson("a", "submitted", bounty = 10)},${inquiryJson("b", "closed", bounty = 30)},
                ${inquiryJson("c", "closed", bounty = 20)},${inquiryJson("d", "cancelled", bounty = 15)},
                ${inquiryJson("e", "in_progress", bounty = 5)}]}""",
            )
        }
        val o = h.repo.overview()
        assertEquals(1, o.openCount)
        assertEquals(1, o.inProgressCount)
        assertEquals(2, o.deliveredCount)
        assertEquals(50, o.raisedUsd)
        assertEquals(15, o.wastedUsd)
        assertNull(o.needsAttention)
    }

    @Test
    fun worldProjectsMissingRouteIsEmpty() = runTest {
        val h = Harness { _, path, _ ->
            assertEquals("/v1/world-projects", path)
            error(HttpStatusCode.NotFound, "not_found", "No route matches that path.")
        }
        assertEquals(emptyList(), h.repo.worldProjects())
    }

    @Test
    fun worldProjectsParsesItems() = runTest {
        val h = Harness { _, _, _ ->
            json("""{"items":[{"id":"w1","title":"Flood dashboard","summary":"Alerts","organization":"Open data","types":["web_app","Data / Script","Unknown"],"url":null}]}""")
        }
        val w = h.repo.worldProjects().single()
        assertEquals(setOf(ProjectType.WebApp, ProjectType.DataScript), w.types)
    }

    @Test
    fun worldProjectsOtherErrorsPropagate() = runTest {
        val h = Harness { _, _, _ -> error(HttpStatusCode.InternalServerError, "internal", "boom") }
        assertEquals("internal", assertFailsWith<ApiException> { h.repo.worldProjects() }.code)
    }

    @Test
    fun profileFromMeAndInquiries() = runTest {
        val h = Harness { _, path, _ ->
            when (path) {
                "/v1/me" -> json(me)
                else -> json("""{"items":[${inquiryJson("a", "closed", bounty = 30)},${inquiryJson("b", "submitted")}]}""")
            }
        }
        val p = h.repo.profile()
        assertEquals("Computer Science", p.department)
        assertEquals("Staff", p.title)
        assertEquals(listOf("CMPS210", "CMPS433"), p.classes)
        assertEquals(2, p.projectsPosted)
        assertEquals(1, p.delivered)
        assertEquals(30, p.raisedUsd)
        val updated = h.repo.updateNotifications(notifyWhenDone = false, notifyWhenStudentJoins = true)
        assertEquals(false, updated.notifyWhenDone)
        assertEquals(false, h.repo.profile().notifyWhenDone)
    }

    @Test
    fun profileListsSeniorEngineersAndDetailMergesPublicProfile() = runTest {
        val h = Harness { _, path, _ ->
            when (path) {
                "/v1/me" -> json(me)
                "/v1/senior-engineers" -> json(
                    """{"items":[
                        {"id":"u-ilias","name":"Ilias","title":"Tech Lead Engineer","bio":"Leads engineering.",
                         "avatar_url":null,"skills":["Kotlin","Go"],"github_username":"illiasab","email":"ilias@bau.edu",
                         "roles":["lead","interviewer"],"future":1},
                        {"id":"u-arian","name":"Arian","skills":[{"name":"React"}],"roles":["mentor"]},
                        {"id":"","name":"dropped"}
                    ]}""",
                )
                "/v1/users/u-ilias" -> json(
                    """{"id":"u-ilias","name":"Ilias","initials":"I","avatar":null,"bio":"Leads BAU BUILD engineering.",
                        "role":"lead","department":"Engineering","skills":["Kotlin","Swift","Go"],"classes":[]}""",
                )
                else -> json("""{"items":[]}""")
            }
        }
        val engineers = h.repo.profile().seniorEngineers
        assertEquals(listOf("Ilias", "Arian"), engineers.map { it.name })
        val ilias = engineers[0]
        assertEquals("Tech Lead Engineer", ilias.title)
        assertEquals("illiasab", ilias.githubUsername)
        assertEquals("ilias@bau.edu", ilias.email)
        assertTrue(ilias.isInterviewer)
        assertEquals(listOf("React"), engineers[1].skills)
        assertEquals(false, engineers[1].isInterviewer)
        assertEquals("", engineers[1].title)

        val full = h.repo.seniorEngineer("u-ilias")
        assertEquals("Leads BAU BUILD engineering.", full.bio)
        assertEquals(listOf("Kotlin", "Swift", "Go"), full.skills)
        // Not in the public profile → from the list.
        assertEquals("Tech Lead Engineer", full.title)
        assertEquals("ilias@bau.edu", full.email)
        assertEquals("illiasab", full.githubUsername)
        assertTrue(h.calls.any { it.method == "GET" && it.path == "/v1/users/u-ilias" })
    }

    @Test
    fun seniorEngineersMissingRouteMeansEmpty() = runTest {
        val h = Harness { _, path, _ ->
            when (path) {
                "/v1/me" -> json(me)
                "/v1/senior-engineers" -> error(HttpStatusCode.NotFound, "not_found", "no route")
                else -> json("""{"items":[]}""")
            }
        }
        assertTrue(h.repo.profile().seniorEngineers.isEmpty())
    }

    @Test
    fun seniorEngineersOtherErrorsKeepProfileWorking() = runTest {
        val h = Harness { _, path, _ ->
            when (path) {
                "/v1/me" -> json(me)
                "/v1/senior-engineers" -> error(HttpStatusCode.InternalServerError, "internal", "boom")
                else -> json("""{"items":[]}""")
            }
        }
        val p = h.repo.profile()
        assertTrue(p.seniorEngineers.isEmpty())
        assertEquals("Computer Science", p.department)
    }

    @Test
    fun seniorEngineerDetailWithoutListUsesPublicFieldsAndErrorsPropagate() = runTest {
        val h = Harness { _, path, _ ->
            when (path) {
                "/v1/users/u-x" -> json("""{"id":"u-x","name":"Arian","bio":"Mentors teams.","role":"lead","skills":"TypeScript, Cloud"}""")
                else -> error(HttpStatusCode.NotFound, "not_found", "nope")
            }
        }
        val arian = h.repo.seniorEngineer("u-x")
        assertEquals("Mentors teams.", arian.bio)
        assertEquals("Lead Engineer", arian.title)
        assertEquals(listOf("TypeScript", "Cloud"), arian.skills)
        assertNull(arian.email)
        assertEquals(404, assertFailsWith<ApiException> { h.repo.seniorEngineer("missing") }.status)
    }
}
