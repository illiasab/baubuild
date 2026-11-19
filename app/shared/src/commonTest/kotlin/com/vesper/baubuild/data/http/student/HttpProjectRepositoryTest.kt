package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.DataChanges
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.Reviewer
import com.vesper.baubuild.core.network.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpProjectRepositoryTest {
    private val backend = StudentMockBackend()
    private val repo = HttpProjectRepository(backend.client) { TEST_NOW }

    @Test
    fun openProjectsFollowsCursorsMapsAndDropsJoined() = runTest {
        backend.on("GET", "/v1/projects") { call ->
            HttpStatusCode.OK to when (call.query["cursor"]) {
                null -> Fixtures.page(
                    listOf(
                        Fixtures.projectDetail(),
                        Fixtures.projectDetail(id = "attendance", membership = "joined"),
                    ),
                    next = "20",
                )
                "20" -> Fixtures.page(
                    listOf(
                        Fixtures.projectDetail(
                            id = "ranking", title = "Rebuild course-search ranking", department = "CMPS433",
                            difficulty = "hard", joining = "approval", reviewedBy = "professor",
                            reviewerLabel = "professor of CMPS433", classId = "cmps433", repoName = "bau/course-search",
                            minScore = 300, bounty = 60,
                        ),
                        Fixtures.projectDetail(id = "shuttle", membership = "pending_approval"),
                    ),
                    next = null,
                )
                else -> error("unexpected cursor")
            }
        }
        backend.on("GET", "/v1/me/classes", body = Fixtures.MY_CLASSES)

        val projects = repo.openProjects()

        assertEquals(listOf("library", "ranking"), projects.map { it.id })
        assertEquals(listOf(null, "20"), backend.callsTo("GET", "/v1/projects").map { it.query["cursor"] })
        val library = projects[0]
        assertEquals("Library", library.owner)
        assertEquals(18, library.bountyUsd)
        assertEquals(Difficulty.Easy, library.difficulty)
        assertEquals(JoinPolicy.Open, library.joinPolicy)
        assertEquals(3, library.dueInDays)
        assertEquals("library-booking", library.repoName)
        assertNull(library.repoFullName) // bare repo name: no owner to build a GitHub full name from
        assertEquals("The library’s booking form accepts dates in the past.", library.summary)
        val ranking = projects[1]
        assertEquals(Difficulty.Hard, ranking.difficulty)
        assertEquals(JoinPolicy.ApprovalRequired, ranking.joinPolicy)
        assertEquals(Reviewer.Professor, ranking.reviewer)
        assertEquals(300, ranking.minScore)
        assertEquals("CMPS433", ranking.classCode) // class id mapped to the code via /v1/me/classes
        assertEquals("bau/course-search", ranking.repoFullName)
        assertEquals("course-search", ranking.repoName)
    }

    @Test
    fun openProjectsSendsDifficultyFilter() = runTest {
        backend.on("GET", "/v1/projects", body = Fixtures.page(emptyList(), null))
        repo.openProjects(Difficulty.Medium)
        assertEquals("medium", backend.calls.single().query["difficulty"])
    }

    @Test
    fun pagingStopsAtCap() = runTest {
        backend.on("GET", "/v1/projects") { call ->
            val next = ((call.query["cursor"]?.toInt() ?: 0) + 20).toString()
            HttpStatusCode.OK to Fixtures.page(listOf(Fixtures.projectDetail(id = "p$next")), next)
        }
        val projects = repo.openProjects()
        assertEquals(MAX_PAGES, projects.size)
    }

    @Test
    fun projectsForClassUsesClassCode() = runTest {
        backend.on(
            "GET", "/v1/classes/CMPS433/projects",
            body = """{"class": ${Fixtures.MY_CLASSES.substringAfter("[").substringBeforeLast("]")},
                      "items": [${Fixtures.projectDetail(id = "ranking", classId = "cmps433")},
                                ${Fixtures.projectDetail(id = "other", classId = "cmps433", membership = "joined")}]}""",
        )
        val projects = repo.projectsForClass("CMPS433")
        assertEquals(listOf("ranking"), projects.map { it.id })
        assertEquals("CMPS433", projects.single().classCode)
    }

    @Test
    fun joinOpenProjectIsInAndBroadcasts() = runTest {
        backend.on("POST", "/v1/projects/library/join", body = """{"result": "joined"}""")
        backend.on("GET", "/v1/projects/library", body = Fixtures.projectDetail(membership = "joined"))
        val signal = async(start = CoroutineStart.UNDISPATCHED) { DataChanges.memberships.first() }

        val membership = repo.join("library")

        assertEquals(MembershipStatus.In, membership.status)
        assertNull(membership.reviewingBy)
        assertEquals("Fix form validation on the room-booking page", membership.project.title)
        withTimeout(1_000) { signal.await() }
    }

    @Test
    fun joinApprovalProjectWaits() = runTest {
        backend.on("POST", "/v1/projects/shuttle/join", body = """{"result": "pending_approval"}""")
        backend.on(
            "GET", "/v1/projects/shuttle",
            body = Fixtures.projectDetail(id = "shuttle", joining = "approval", membership = "pending_approval"),
        )
        val membership = repo.join("shuttle")
        assertEquals(MembershipStatus.WaitingForApproval, membership.status)
        assertEquals("dev lead", membership.reviewingBy)
    }

    @Test
    fun joinErrorsCarryTheServerCodeAndDoNotBroadcast() = runTest {
        backend.on(
            "POST", "/v1/projects/ranking/join", HttpStatusCode.Forbidden,
            Fixtures.error("score_too_low", "This project needs a score of 300. Yours is 0."),
        )
        val e = assertFailsWith<ApiException> { repo.join("ranking") }
        assertEquals(403, e.status)
        assertEquals("score_too_low", e.code)
        assertTrue(backend.callsTo("GET", "/v1/projects/ranking").isEmpty())
    }

    @Test
    fun projectNotFound() = runTest {
        backend.on("GET", "/v1/projects/nope", HttpStatusCode.NotFound, Fixtures.error("not_found"))
        assertEquals("not_found", assertFailsWith<ApiException> { repo.project("nope") }.code)
    }
}
