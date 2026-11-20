package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.DataChanges
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.http.student.dto.ClassDto
import com.vesper.baubuild.data.http.student.dto.ClassProjectsDto
import com.vesper.baubuild.data.http.student.dto.JoinResultDto
import com.vesper.baubuild.data.http.student.dto.PageDto
import com.vesper.baubuild.data.http.student.dto.ProjectDto
import com.vesper.baubuild.data.http.student.dto.Wire
import com.vesper.baubuild.data.repository.ProjectRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ProjectRepository] on the BAU BUILD backend.
 *
 * - `GET /v1/projects?difficulty=&cursor=` (all pages) / `GET /v1/classes/{id}/projects`: projects the user is
 *   already in or waiting on (`my_membership != null`) are dropped — they live in My Work.
 * - `GET /v1/projects/{id}` for detail.
 * - `POST /v1/projects/{id}/join` → `{"result": "joined" | "pending_approval"}`, then the project is re-read to
 *   build the [Membership]; [DataChanges.membershipsChanged] fires on success. 403 codes: `score_too_low`,
 *   `payment_required`, `class_not_enrolled`; 409 `project_closed`; 404 `not_found` (as [com.vesper.baubuild.core.network.ApiException]).
 *
 * Projects carry a backend class *id*; it is shown as the class *code* using `GET /v1/me/classes` (cached).
 */
class HttpProjectRepository(
    private val client: HttpClient,
    private val now: NowProvider = systemNow,
) : ProjectRepository {
    private val classCodesLock = Mutex()
    private var classCodes: Map<String, String>? = null

    override suspend fun openProjects(difficulty: Difficulty?): List<Project> {
        val items = client.getAllPages<ProjectDto>("v1/projects") {
            difficulty?.let { parameter("difficulty", Wire.difficulty(it)) }
        }.filter { it.isOpenToViewer }
        return items.toDomain()
    }

    override suspend fun project(id: String): Project {
        val dto: ProjectDto = apiCall { client.get("v1/projects/${id.encodeURLPathPart()}").body() }
        return listOf(dto).toDomain().single()
    }

    override suspend fun projectsForClass(classCode: String): List<Project> {
        val dto: ClassProjectsDto = apiCall { client.get("v1/classes/${classCode.encodeURLPathPart()}/projects").body() }
        // Every item belongs to this class, so they all get its display code.
        val code = dto.bauClass?.code?.ifBlank { null } ?: classCode
        val n = now()
        return dto.items.filter { it.isOpenToViewer }.map { it.toDomain(n).copy(classCode = code) }
    }

    override suspend fun join(projectId: String): Membership {
        val result: JoinResultDto = apiCall { client.post("v1/projects/${projectId.encodeURLPathPart()}/join").body() }
        DataChanges.membershipsChanged()
        val joined = result.result == "joined"
        val dto: ProjectDto? = try {
            apiCall { client.get("v1/projects/${projectId.encodeURLPathPart()}").body<ProjectDto>() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // the join itself succeeded; don't report it as failed because the re-read did
        }
        val project = dto?.let { listOf(it).toDomain().single() } ?: Project(
            id = projectId, title = "", owner = "", summary = "", description = "", bountyUsd = 0,
            difficulty = Difficulty.Easy, minScore = 0,
            joinPolicy = if (joined) JoinPolicy.Open else JoinPolicy.ApprovalRequired, dueInDays = 0, repoFullName = null,
        )
        return Membership(
            project = project,
            status = if (joined) MembershipStatus.In else MembershipStatus.WaitingForApproval,
            reviewingBy = if (joined) null else dto?.reviewerLabel ?: "pending",
        )
    }

    private suspend fun List<ProjectDto>.toDomain(): List<Project> {
        val codes = if (any { it.classId != null && it.classCode.isNullOrBlank() }) classCodes() else emptyMap()
        val n = now()
        return map { it.toDomain(n, codes) }
    }

    /** Class id → code for the user's classes; best effort (an empty map just shows raw ids). */
    private suspend fun classCodes(): Map<String, String> = classCodesLock.withLock {
        classCodes ?: try {
            apiCall { client.get("v1/me/classes").body<PageDto<ClassDto>>() }.items
                .associate { it.id to it.code.ifBlank { it.id } }
                .also { classCodes = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
