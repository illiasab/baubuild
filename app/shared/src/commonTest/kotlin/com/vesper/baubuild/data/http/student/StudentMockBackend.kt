package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.network.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Instant

/** Fixed "now" for due-date math: fixtures' due dates are relative to this. */
internal val TEST_NOW: Instant = Instant.parse("2026-11-19T10:00:00Z")

internal data class Call(val method: String, val path: String, val query: Map<String, String>, val body: String?)

/**
 * MockEngine-backed client configured like `createHttpClient` (AppJson, relative paths against a base URL,
 * `expectSuccess = true`). Routes are matched on "METHOD /path" (query ignored); [cursorPages] serves
 * `?cursor=` pages for a path.
 */
internal class StudentMockBackend {
    val calls = mutableListOf<Call>()
    private val routes = mutableMapOf<String, (Call) -> Pair<HttpStatusCode, String>>()

    fun on(method: String, path: String, status: HttpStatusCode = HttpStatusCode.OK, body: String) {
        routes["$method $path"] = { status to body }
    }

    fun on(method: String, path: String, handler: (Call) -> Pair<HttpStatusCode, String>) {
        routes["$method $path"] = handler
    }

    val client: HttpClient = HttpClient(MockEngine { req ->
        val call = Call(
            req.method.value,
            req.url.encodedPath,
            req.url.parameters.names().associateWith { req.url.parameters[it]!! },
            (req.body as? TextContent)?.text,
        )
        calls += call
        val handler = routes["${call.method} ${call.path}"]
        val (status, body) = handler?.invoke(call)
            ?: (HttpStatusCode.NotFound to """{"error":{"code":"not_found","message":"No route matches that path."}}""")
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        install(ContentNegotiation) { json(AppJson) }
        defaultRequest { url("https://api.example.test/") }
        expectSuccess = true
    }

    fun callsTo(method: String, path: String) = calls.filter { it.method == method && it.path == path }
}

/** Sample payloads shaped exactly like the backend's responses (seed data). */
internal object Fixtures {
    fun projectDetail(
        id: String = "library",
        title: String = "Fix form validation on the room-booking page",
        department: String = "Library",
        difficulty: String = "easy",
        joining: String = "everyone",
        reviewedBy: String = "ai_then_lead",
        reviewerLabel: String = "dev lead",
        classId: String? = null,
        repoName: String? = "library-booking",
        membership: String? = null,
        minScore: Int = 0,
        bounty: Int = 18,
        dueAt: String = "2026-11-22T10:00:00Z",
    ) = """
        {
          "id": "$id",
          "department": "$department",
          "title": "$title",
          "bounty": $bounty,
          "difficulty": "$difficulty",
          "min_score": $minScore,
          "min_score_label": "${if (minScore <= 0) "Any score" else "Score $minScore+"}",
          "due_at": "$dueAt",
          "urgent": false,
          "art_key": "$id",
          "status": "open",
          "description": "The library’s booking form accepts dates in the past. Find the check, fix it, and add a test.",
          "class_id": ${classId?.let { "\"$it\"" } ?: "null"},
          "repo": {"provider": "github", "name": ${repoName?.let { "\"$it\"" } ?: "null"}, "connected": false},
          "joining": "$joining",
          "reviewed_by": "$reviewedBy",
          "reviewer_label": "$reviewerLabel",
          "is_bookmarked": false,
          "my_membership": ${membership?.let { """{"status": "$it"}""" } ?: "null"},
          "eligibility": {"eligible": true, "reason": null},
          "some_future_field": {"nested": [1, 2, 3]}
        }
    """.trimIndent()

    fun page(items: List<String>, next: String?) =
        """{"items": [${items.joinToString(",")}], "next_cursor": ${next?.let { "\"$it\"" } ?: "null"}}"""

    const val MY_CLASSES = """
        {"items": [{"id": "cmps433", "code": "CMPS433", "name": "Course search and ranking", "term": "Fall 2026",
                    "instructor": "CMPS faculty", "open_project_count": 1}]}
    """

    const val URGENT = """
        {"items": [
          {"id": "shuttle", "title": "Build a shuttle timetable widget for the student portal", "bounty": 35,
           "difficulty": "hard", "min_score_label": "Any score", "department": "Campus Ops", "art_key": "shuttle"},
          {"id": "transcript", "title": "Speed up the transcript request form", "bounty": 45,
           "difficulty": "hard", "min_score_label": "Score 250+", "department": "Registrar", "art_key": "transcript"}
        ]}
    """

    const val MY_WORK = """
        {
          "in_progress": [
            {"id": "library", "title": "Fix form validation on the room-booking page", "department": "Library",
             "bounty": 18, "task_count": 3, "open_task_count": 1}
          ],
          "awaiting_approval": [
            {"id": "shuttle", "title": "Build a shuttle timetable widget for the student portal",
             "status": "pending_approval", "reviewer_label": "dev lead", "bounty": 35}
          ]
        }
    """

    const val TASKS = """
        {"items": [
          {"id": "t1", "project_id": "library", "title": "Reject past dates in the booking form", "status": "in_progress", "lane": "assigned", "due_at": "2026-11-22T10:00:00Z"},
          {"id": "t2", "project_id": "library", "title": "Add a test for the date check", "status": "todo", "lane": "backlog", "due_at": "2026-11-22T10:00:00Z"},
          {"id": "t3", "project_id": "library", "title": "Announce the error", "status": "todo", "lane": "assigned", "due_at": null},
          {"id": "t4", "project_id": "library", "title": "Old", "status": "done", "lane": "assigned", "due_at": null},
          {"id": "t5", "project_id": "library", "title": "Future", "status": "blocked", "lane": "assigned", "due_at": null}
        ]}
    """

    const val CHANGES = """
        {
          "connected": true,
          "repo": "library-booking",
          "branches": [
            {"name": "fix-date-validation", "commits": [
              {"id": "s2", "message": "fix-date-validation", "state": "sent_back", "diff_url": "https://github.com/library-booking/compare/fix-date-validation", "created_at": "2026-11-18T10:00:00Z"},
              {"id": "s1", "message": "fix-date-validation", "state": "approved", "diff_url": null, "created_at": "2026-11-17T10:00:00Z"}
            ]},
            {"name": "add-test", "commits": [
              {"id": "s3", "message": "add-test", "state": "pending", "diff_url": null, "created_at": "2026-11-18T11:00:00Z"}
            ]}
          ],
          "github_commits": [
            {"sha": "abcdef1234567", "message": "Reject past dates\n\nLonger body", "url": "https://github.com/x/y/commit/abcdef1"}
          ]
        }
    """

    fun submission(id: String, projectId: String, lead: String, paidAt: String?, comment: String? = null) = """
        {
          "id": "$id",
          "project_id": "$projectId",
          "diff_url": "https://github.com/library-booking/compare/fix-date-validation",
          "created_at": "2026-11-18T10:00:00Z",
          "steps": [
            {"step": "pushed", "branch": "fix-date-validation"},
            {"step": "ai_review", "status": "complete", "findings": [
              {"kind": "pass", "text": "The branch is attached to this project and ready for a dev lead."},
              {"kind": "warn", "text": "Worth a look: announce the error to screen readers."}
            ]},
            {"step": "lead_decision", "status": "$lead",
             "reviewer": ${if (lead == "pending") "null" else """{"id": "u9", "name": "Dana Lead", "initials": "DL"}"""},
             "comment": ${comment?.let { "\"$it\"" } ?: "null"}},
            {"step": "bounty", "amount": 18, "paid_at": ${paidAt?.let { "\"$it\"" } ?: "null"}}
          ]
        }
    """.trimIndent()

    fun notification(id: String, text: String, read: Boolean, createdAt: String) =
        """{"id": "$id", "text": "$text", "read": $read, "created_at": "$createdAt"}"""

    const val ME = """
        {"id": "u1", "name": "Sam Student", "initials": "SS", "avatar": null, "bio": "Builds things.",
         "role": "student", "department": null, "skills": ["Kotlin", "SQL"], "classes": ["CMPS433"],
         "email": "sam@stu.bau.edu"}
    """

    const val HOME = """
        {
          "greeting_name": "Sam",
          "urgent": [{"id": "shuttle", "title": "Build a shuttle timetable widget", "bounty": 35, "difficulty": "hard",
                      "min_score_label": "Any score", "department": "Campus Ops", "art_key": "shuttle"}],
          "difficulty_counts": {"easy": 3, "medium": 1, "hard": 3},
          "open_projects": [{"id": "faq", "department": "Admissions", "title": "Repair broken links on the FAQ", "bounty": 18,
                             "difficulty": "easy", "min_score": 0, "min_score_label": "Any score",
                             "due_at": "2026-11-23T10:00:00Z", "urgent": false, "art_key": "faq", "status": "open"}]
        }
    """

    const val SEARCH = """
        {
          "projects": [{"id": "faq", "department": "Admissions", "title": "Repair broken links on the FAQ", "bounty": 18,
                        "difficulty": "easy", "min_score": 0, "min_score_label": "Any score",
                        "due_at": "2026-11-23T10:00:00Z", "urgent": false, "art_key": "faq", "status": "open"}],
          "classes": [{"id": "cmps433", "code": "CMPS433", "name": "Course search and ranking", "term": "Fall 2026",
                       "instructor": "CMPS faculty", "open_project_count": 1}],
          "inquiries": [{"id": "inq-shuttle", "title": "Build a shuttle timetable widget", "description": "d",
                         "department": "Campus Ops", "difficulty": "hard", "bounty": 35, "is_urgent": true,
                         "due_at": null, "repo_url": null, "min_score": 0, "joining": "approval",
                         "reviewer_type": "ai_then_lead", "status": "published", "project_id": "shuttle",
                         "created_at": "2026-11-18T10:00:00Z"}]
        }
    """

    fun error(code: String, message: String = "Nope.") = """{"error": {"code": "$code", "message": "$message"}}"""
}
