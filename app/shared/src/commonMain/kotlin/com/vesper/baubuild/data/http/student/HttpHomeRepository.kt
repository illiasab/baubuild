package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.model.BauClass
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.http.student.dto.HomeDto
import com.vesper.baubuild.data.http.student.dto.SearchDto
import com.vesper.baubuild.data.http.student.dto.Wire
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

/** First paint of the Projects tab in one round trip. */
data class HomeFeed(
    val greetingName: String,
    val urgent: List<Inquiry>,
    val difficultyCounts: Map<Difficulty, Int>,
    /** Up to 6 open projects (card form: no description / join policy / repo — open the detail for those). */
    val openProjects: List<Project>,
)

/** Server-side search over projects, classes and published inquiries. */
data class SearchResults(
    val projects: List<Project>,
    val classes: List<BauClass>,
    val inquiries: List<Inquiry>,
)

/** Optional student endpoints that have no repository interface yet. */
interface HomeRepository {
    suspend fun home(): HomeFeed

    /** [query] must be non-blank (400 `invalid_query` otherwise). */
    suspend fun search(query: String): SearchResults
}

/**
 * - `GET /v1/home` → `{greeting_name, urgent[], difficulty_counts{easy,medium,hard}, open_projects[]}`.
 *   Note: unlike `GET /v1/projects`, these cards carry no membership, so joined projects are not filtered out.
 * - `GET /v1/search?q=` → `{projects[], classes[], inquiries[]}` (≤ 20 each, card form).
 */
class HttpHomeRepository(
    private val client: HttpClient,
    private val now: NowProvider = systemNow,
) : HomeRepository {

    override suspend fun home(): HomeFeed {
        val dto: HomeDto = apiCall { client.get("v1/home").body() }
        val n = now()
        return HomeFeed(
            greetingName = dto.greetingName,
            urgent = dto.urgent.map { it.toDomain() },
            difficultyCounts = Difficulty.entries.associateWith { dto.difficultyCounts[Wire.difficulty(it)] ?: 0 },
            openProjects = dto.openProjects.map { it.toDomain(n) },
        )
    }

    override suspend fun search(query: String): SearchResults {
        val dto: SearchDto = apiCall { client.get("v1/search") { parameter("q", query.trim()) }.body() }
        val n = now()
        return SearchResults(
            projects = dto.projects.map { it.toDomain(n) },
            classes = dto.classes.map { it.toDomain() },
            inquiries = dto.inquiries.map { it.toDomain() },
        )
    }
}
