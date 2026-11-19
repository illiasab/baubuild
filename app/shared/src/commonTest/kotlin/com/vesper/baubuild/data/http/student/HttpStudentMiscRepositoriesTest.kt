package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.NewInquiry
import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.data.repository.NotificationType
import com.vesper.baubuild.data.repository.ProfileUpdate
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpInquiryRepositoryTest {
    private val backend = StudentMockBackend()
    private val repo = HttpInquiryRepository(backend.client)

    @Test
    fun urgentMapsCardsAndDropsMyProjects() = runTest {
        backend.on("GET", "/v1/inquiries/urgent", body = Fixtures.URGENT)
        backend.on("GET", "/v1/me/work", body = Fixtures.MY_WORK) // user waits on "shuttle"
        val urgent = repo.urgentInquiries()
        val t = urgent.single()
        assertEquals("transcript", t.id)
        assertEquals("transcript", t.projectId)
        assertEquals("Registrar", t.department)
        assertEquals(45, t.bountyUsd)
        assertEquals(Difficulty.Hard, t.difficulty)
        assertEquals(250, t.minScore)
        assertTrue(t.isUrgent)
    }

    @Test
    fun urgentStillShowsWhenWorkFails() = runTest {
        backend.on("GET", "/v1/inquiries/urgent", body = Fixtures.URGENT)
        backend.on("GET", "/v1/me/work", HttpStatusCode.InternalServerError, Fixtures.error("internal"))
        assertEquals(2, repo.urgentInquiries().size)
    }

    @Test
    fun submitSendsEveryRequiredField() = runTest {
        backend.on(
            "POST", "/v1/inquiries", HttpStatusCode.Created,
            """{"id": "i1", "title": "Room finder", "description": "Find rooms", "department": "Library",
                "difficulty": "easy", "bounty": 40, "is_urgent": true, "due_at": null, "repo_url": null,
                "min_score": 0, "joining": "everyone", "reviewer_type": "ai_then_lead", "status": "submitted",
                "project_id": null, "created_at": "2026-11-19T10:00:00Z"}""",
        )
        val inquiry = repo.submit(NewInquiry("Room finder", "Find rooms", "Library", isUrgent = true, suggestedBountyUsd = 40))
        val sent = Json.parseToJsonElement(backend.calls.single().body!!).jsonObject
        assertEquals(
            setOf("title", "description", "department", "difficulty", "bounty", "is_urgent", "min_score", "joining", "reviewer_type"),
            sent.keys,
        )
        assertEquals(40, sent["bounty"]!!.jsonPrimitive.int)
        assertTrue(sent["is_urgent"]!!.jsonPrimitive.boolean)
        assertEquals("i1", inquiry.id)
        assertEquals(40, inquiry.bountyUsd)
        assertNull(inquiry.projectId)
    }

    @Test
    fun submitAsStudentIsRoleRequired() = runTest {
        backend.on("POST", "/v1/inquiries", HttpStatusCode.Forbidden, Fixtures.error("role_required"))
        val e = assertFailsWith<ApiException> { repo.submit(NewInquiry("t", "d", "x", false)) }
        assertEquals("role_required", e.code)
    }
}

class HttpClassRepositoryTest {
    @Test
    fun myClasses() = runTest {
        val backend = StudentMockBackend()
        backend.on("GET", "/v1/me/classes", body = Fixtures.MY_CLASSES)
        val c = HttpClassRepository(backend.client).myClasses().single()
        assertEquals("CMPS433", c.code)
        assertEquals("CMPS faculty", c.professor)
        assertEquals("Fall 2026", c.term)
        assertEquals(1, c.openProjectCount)
    }

    @Test
    fun unauthorized() = runTest {
        val backend = StudentMockBackend()
        backend.on("GET", "/v1/me/classes", HttpStatusCode.Unauthorized, Fixtures.error("unauthorized"))
        val e = assertFailsWith<ApiException> { HttpClassRepository(backend.client).myClasses() }
        assertEquals(401, e.status)
        assertEquals("unauthorized", e.code)
    }
}

class HttpNotificationRepositoryTest {
    @Test
    fun pagesAndMaps() = runTest {
        val backend = StudentMockBackend()
        backend.on("GET", "/v1/notifications") { call ->
            HttpStatusCode.OK to when (call.query["cursor"]) {
                null -> Fixtures.page(
                    listOf(Fixtures.notification("n2", "You’re in “Fix form validation”.", false, "2026-11-19T09:00:00Z")),
                    "20",
                )
                else -> Fixtures.page(
                    listOf(Fixtures.notification("n1", "AI review finished on fix-date. A dev lead has the summary.", true, "2026-11-18T09:00:00Z")),
                    null,
                )
            }
        }
        val items = HttpNotificationRepository(backend.client).notifications()
        assertEquals(listOf("n2", "n1"), items.map { it.id })
        assertEquals(NotificationType.JoinApproved, items[0].type)
        assertEquals(NotificationType.ReviewUpdate, items[1].type)
        assertFalse(items[0].read)
        assertEquals(kotlin.time.Instant.parse("2026-11-19T09:00:00Z").toEpochMilliseconds(), items[0].createdAtEpochMs)
    }

    @Test
    fun markReadAndCount() = runTest {
        val backend = StudentMockBackend()
        backend.on("POST", "/v1/notifications/read", body = """{"unread": 0}""")
        backend.on("GET", "/v1/notifications/unread-count", body = """{"unread": 4}""")
        val repo = HttpNotificationRepository(backend.client)
        repo.markRead("n1")
        assertEquals("n1", Json.parseToJsonElement(backend.calls.last().body!!).jsonObject["ids"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(0, repo.markAllRead())
        assertEquals("""{"all":true}""", backend.calls.last().body)
        assertEquals(4, repo.unreadCount())
    }
}

class HttpProfileRepositoryTest {
    private val sam = User(
        id = "u1", displayName = "Sam Student", email = "sam@stu.bau.edu", kind = AccountKind.BauStudent,
        role = Role.Student, score = 120, githubUsername = "old", avatarUrl = "https://x/a.jpg",
    )

    @Test
    fun sendsOnlyBioAndSkillsAndKeepsOtherFieldsLocal() = runTest {
        val backend = StudentMockBackend()
        backend.on("PATCH", "/v1/me", body = Fixtures.ME)
        val repo = HttpProfileRepository(backend.client, { sam })

        val user = repo.updateProfile(
            ProfileUpdate("Sam S.", "Builds things.", listOf("Kotlin", " SQL "), githubUsername = "samdev", removeAvatar = true),
        )

        val sent = Json.parseToJsonElement(backend.calls.single().body!!).jsonObject
        assertEquals(setOf("bio", "skills"), sent.keys)
        assertEquals(listOf("Kotlin", "SQL"), sent["skills"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Sam S.", user.displayName)
        assertEquals("samdev", user.githubUsername)
        assertNull(user.avatarUrl)
        assertEquals(120, user.score) // not in the backend response: kept from the session user
        assertEquals(listOf("CMPS433"), user.classCodes)
    }

    @Test
    fun blankBioAndEmptySkillsAreNotSent() = runTest {
        val backend = StudentMockBackend()
        backend.on("PATCH", "/v1/me", body = Fixtures.ME)
        HttpProfileRepository(backend.client, { sam }).updateProfile(ProfileUpdate("Sam", " ", emptyList(), ""))
        assertEquals("{}", backend.calls.single().body)
    }

    @Test
    fun invalidBody() = runTest {
        val backend = StudentMockBackend()
        backend.on("PATCH", "/v1/me", HttpStatusCode.BadRequest, Fixtures.error("invalid_body"))
        val e = assertFailsWith<ApiException> {
            HttpProfileRepository(backend.client, { sam }).updateProfile(ProfileUpdate("Sam", "x", listOf("a"), ""))
        }
        assertEquals("invalid_body", e.code)
    }

    @Test
    fun uploadAvatarIsLocal() = runTest {
        val backend = StudentMockBackend()
        val url = HttpProfileRepository(backend.client, { sam }).uploadAvatar(byteArrayOf(1, 2), "image/png")
        assertTrue(url.startsWith("memory://"))
        assertTrue(backend.calls.isEmpty())
    }
}

class HttpHomeRepositoryTest {
    @Test
    fun homeAndSearch() = runTest {
        val backend = StudentMockBackend()
        backend.on("GET", "/v1/home", body = Fixtures.HOME)
        backend.on("GET", "/v1/search", body = Fixtures.SEARCH)
        val repo = HttpHomeRepository(backend.client) { TEST_NOW }

        val home = repo.home()
        assertEquals("Sam", home.greetingName)
        assertEquals(mapOf(Difficulty.Easy to 3, Difficulty.Medium to 1, Difficulty.Hard to 3), home.difficultyCounts)
        assertEquals("shuttle", home.urgent.single().projectId)
        assertEquals(4, home.openProjects.single().dueInDays)

        val results = repo.search(" faq ")
        assertEquals("faq", backend.calls.last().query["q"])
        assertEquals("faq", results.projects.single().id)
        assertEquals("CMPS433", results.classes.single().code)
        assertEquals("shuttle", results.inquiries.single().projectId)
    }

    @Test
    fun emptyQueryIsInvalid() = runTest {
        val backend = StudentMockBackend()
        backend.on("GET", "/v1/search", HttpStatusCode.BadRequest, Fixtures.error("invalid_query"))
        assertEquals("invalid_query", assertFailsWith<ApiException> { HttpHomeRepository(backend.client).search("") }.code)
    }
}
