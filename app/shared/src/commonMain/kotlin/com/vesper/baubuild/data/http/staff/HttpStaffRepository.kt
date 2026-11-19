package com.vesper.baubuild.data.http.staff

import com.vesper.baubuild.core.DataChanges
import com.vesper.baubuild.core.model.InquiryDraft
import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.ProjectFor
import com.vesper.baubuild.core.model.RefinedInquiry
import com.vesper.baubuild.core.model.RewardType
import com.vesper.baubuild.core.model.SeniorEngineer
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.core.model.StaffOverview
import com.vesper.baubuild.core.model.StaffProfile
import com.vesper.baubuild.core.model.WorldProject
import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.http.staff.dto.CreateInquiryRequest
import com.vesper.baubuild.data.http.staff.dto.InquiryDescription
import com.vesper.baubuild.data.http.staff.dto.InquiryDto
import com.vesper.baubuild.data.http.staff.dto.InquiryListDto
import com.vesper.baubuild.data.http.staff.dto.MeDto
import com.vesper.baubuild.data.http.staff.dto.WorldProjectDto
import com.vesper.baubuild.data.http.staff.dto.WorldProjectListDto
import com.vesper.baubuild.data.http.staff.dto.deadlineToDueAt
import com.vesper.baubuild.data.http.staff.dto.parseSeniorEngineerList
import com.vesper.baubuild.data.http.staff.dto.toSeniorEngineer
import com.vesper.baubuild.data.http.staff.dto.toStaffInquiry
import com.vesper.baubuild.data.http.staff.dto.toWorldProject
import com.vesper.baubuild.data.http.staff.dto.wire
import com.vesper.baubuild.data.repository.StaffRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * [StaffRepository] backed by the BAU BUILD API (contract: docs/openapi.json + docs/index.html §7 "Staff inquiries").
 * Every call goes through [apiCall], so failures surface as [ApiException] with the backend's error `code`
 * (`role_required` 403 for non-staff, `not_found` 404, `inquiry_locked` 409 when editing/deleting a non-submitted
 * inquiry, `invalid_body` 400).
 *
 * Endpoint mapping:
 * - [inquiries]        → `GET /v1/inquiries/mine` (`{items}`), preceded by on-device drafts
 * - [inquiry]          → `GET /v1/inquiries/{id}`
 * - [post]             → `POST /v1/inquiries` (backend status `submitted`; `department` from `GET /v1/me`)
 * - [updateDetails]    → `PATCH /v1/inquiries/{id}` (only while `submitted`)
 * - [cancel]           → `DELETE /v1/inquiries/{id}` (204; only while `submitted`; the row is gone afterwards)
 * - [worldProjects]    → `GET /v1/world-projects` (404 → empty list until it ships; the UI hides the section)
 * - [profile]          → `GET /v1/me` (+ `classes` codes from the same payload) and `GET /v1/inquiries/mine` for counts,
 *                        plus `GET /v1/senior-engineers` (PROPOSED; 404 → empty list, the UI hides the section)
 * - [seniorEngineer]   → `GET /v1/users/{id}` (public profile, exists), merged over the list item
 *
 * Backend gaps covered on the client (replace when the endpoints exist):
 * - [refine]           → CLIENT-SIDE FALLBACK [heuristicRefine]; wanted `POST /v1/staff/inquiries/refine`
 * - [saveDraft]        → kept in memory on this device; wanted `POST /v1/staff/inquiries/drafts`
 * - [overview]         → computed from [inquiries]; wanted `GET /v1/staff/overview`
 * - [resolveBlocker]   → no blockers/progress exist on the backend yet; returns the inquiry unchanged
 * - [updateNotifications] → in-memory prefs; wanted `PATCH /v1/staff/profile/notifications`
 *
 * App-only draft fields (acceptance checks, project types, class project/code, non-cash rewards) are written into
 * the inquiry `description` footer — see [InquiryDescription].
 */
class HttpStaffRepository(private val client: HttpClient) : StaffRepository {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; isLenient = true }

    private val lock = Mutex()
    private val localDrafts = mutableListOf<StaffInquiry>()
    private var nextDraft = 1
    private var notifyWhenDone = true
    private var notifyWhenStudentJoins = true
    private var cachedDepartment: String? = null
    /** Last senior-engineer list, by id: fills fields the public profile lacks and covers list outages. */
    private var cachedEngineers: List<SeniorEngineer> = emptyList()

    // ---- transport ---------------------------------------------------------------------------------------

    private suspend fun send(method: HttpMethod, path: String, body: String? = null): String = apiCall {
        client.request {
            this.method = method
            url(path) // relative to the client's base URL (defaultRequest), e.g. https://api.baubuild.vesperconnect.org/
            expectSuccess = true
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }.bodyAsText()
    }

    private fun id(id: String) = id.encodeURLPathPart()

    // ---- inquiries ---------------------------------------------------------------------------------------

    private suspend fun remoteInquiries(): List<StaffInquiry> =
        json.decodeFromString(InquiryListDto.serializer(), send(HttpMethod.Get, "v1/inquiries/mine")).items.map { it.toStaffInquiry() }

    override suspend fun inquiries(): List<StaffInquiry> {
        val remote = remoteInquiries()
        return lock.withLock { localDrafts.toList() } + remote
    }

    override suspend fun inquiry(id: String): StaffInquiry {
        localDraft(id)?.let { return it }
        return decodeInquiry(send(HttpMethod.Get, "v1/inquiries/${id(id)}"))
    }

    override suspend fun refine(draft: InquiryDraft): RefinedInquiry = heuristicRefine(draft)

    /** The backend has no refine endpoint yet; [heuristicRefine] is a fallback algorithm, not AI. */
    override val aiRefineAvailable: Boolean get() = false

    override suspend fun post(draft: InquiryDraft, refined: RefinedInquiry): StaffInquiry {
        val difficulty = refined.difficulty
        val request = CreateInquiryRequest(
            title = refined.title.ifBlank { draft.title }.trim(),
            description = InquiryDescription.compose(
                body = refined.brief.ifBlank { draft.explanation },
                done = refined.doneLooksLike,
                types = refined.types.ifEmpty { draft.types },
                projectFor = draft.projectFor,
                classCode = draft.classCode,
                draft = draft,
            ),
            department = department(),
            difficulty = difficulty.wire(),
            bounty = bountyOf(draft),
            isUrgent = draft.priority == InquiryPriority.Urgent,
            dueAt = deadlineToDueAt(refined.deadline ?: draft.deadline),
            minScore = refined.minScore.coerceIn(0, 500),
            joining = JOINING,
            reviewerType = reviewerTypeOf(draft),
        )
        val created = decodeInquiry(send(HttpMethod.Post, "v1/inquiries", json.encodeToString(CreateInquiryRequest.serializer(), request)))
        lock.withLock { localDrafts.removeAll { it.draft == draft } }
        DataChanges.inquiriesChanged()
        return created
    }

    override suspend fun saveDraft(draft: InquiryDraft): StaffInquiry {
        val saved = lock.withLock {
            StaffInquiry(
                id = "$LOCAL_DRAFT_PREFIX${nextDraft++}", draft = draft, status = InquiryStatus.Draft,
                latest = "Draft · on this device", rewardLabel = rewardLabel(draft),
            ).also { localDrafts.add(0, it) }
        }
        DataChanges.inquiriesChanged()
        return saved
    }

    override suspend fun updateDetails(id: String, draft: InquiryDraft): StaffInquiry {
        if (isLocal(id)) {
            val updated = lock.withLock {
                val index = localDrafts.indexOfFirst { it.id == id }
                if (index < 0) throw notFound()
                localDrafts[index].copy(draft = draft, rewardLabel = rewardLabel(draft)).also { localDrafts[index] = it }
            }
            DataChanges.inquiriesChanged()
            return updated
        }
        val body = buildJsonObject {
            put("title", draft.title.trim())
            put(
                "description",
                InquiryDescription.compose(
                    body = draft.explanation,
                    done = splitDoneItems(draft.expectation),
                    types = draft.types,
                    projectFor = draft.projectFor,
                    classCode = draft.classCode,
                    draft = draft,
                ),
            )
            draft.difficulty?.let { put("difficulty", it.wire()) }
            put("bounty", bountyOf(draft))
            put("is_urgent", draft.priority == InquiryPriority.Urgent)
            put("due_at", deadlineToDueAt(draft.deadline)?.let(::JsonPrimitive) ?: JsonNull)
            put("reviewer_type", reviewerTypeOf(draft))
        }
        val updated = decodeInquiry(send(HttpMethod.Patch, "v1/inquiries/${id(id)}", body.toString()))
        DataChanges.inquiriesChanged()
        return updated
    }

    /**
     * The backend has no "cancelled" state: a submitted inquiry is withdrawn with `DELETE /v1/inquiries/{id}` and
     * disappears from the list. Approved/published ones answer 409 `inquiry_locked`. The returned value is the
     * last-known inquiry marked [InquiryStatus.Cancelled] so the screen can show the result.
     */
    override suspend fun cancel(id: String): StaffInquiry {
        if (isLocal(id)) {
            val removed = lock.withLock {
                val draft = localDrafts.firstOrNull { it.id == id } ?: throw notFound()
                localDrafts.remove(draft)
                draft
            }
            DataChanges.inquiriesChanged()
            return removed.copy(status = InquiryStatus.Cancelled, latest = "Draft deleted")
        }
        val current = inquiry(id)
        send(HttpMethod.Delete, "v1/inquiries/${id(id)}")
        DataChanges.inquiriesChanged()
        return current.copy(status = InquiryStatus.Cancelled, latest = "Withdrawn")
    }

    /** Backend gap: no progress/blockers yet, so there is nothing to resolve. Wanted `POST /v1/staff/inquiries/{id}/blockers/{bid}/resolve`. */
    override suspend fun resolveBlocker(inquiryId: String, blockerId: String): StaffInquiry = inquiry(inquiryId)

    // ---- overview / world projects -------------------------------------------------------------------------

    /**
     * Computed on the client until `GET /v1/staff/overview` exists: raised = rewards of Done inquiries,
     * wasted = rewards of Cancelled ones (0 today — withdrawn inquiries are deleted on the backend).
     */
    override suspend fun overview(): StaffOverview = computeOverview(inquiries())

    /**
     * `GET /v1/world-projects` — accepts `[...]` or `{items:[...]}`. Not live yet: 404 (route missing) → empty
     * list, which makes Overview hide the section. Other errors propagate as [ApiException].
     */
    override suspend fun worldProjects(): List<WorldProject> {
        val text = try {
            send(HttpMethod.Get, "v1/world-projects")
        } catch (e: ApiException) {
            if (e.status == 404) return emptyList() else throw e
        }
        val element = json.parseToJsonElement(text)
        val items = when (element) {
            is JsonArray -> json.decodeFromJsonElement<List<WorldProjectDto>>(element)
            is JsonObject -> json.decodeFromJsonElement<WorldProjectListDto>(element).items
            else -> emptyList()
        }
        return items.filter { it.id.isNotBlank() && it.title.isNotBlank() }.map { it.toWorldProject() }
    }

    // ---- profile -------------------------------------------------------------------------------------------

    private suspend fun me(): MeDto =
        json.decodeFromString(MeDto.serializer(), send(HttpMethod.Get, "v1/me")).also { me ->
            lock.withLock { cachedDepartment = me.department?.takeIf { it.isNotBlank() } }
        }

    private suspend fun department(): String =
        lock.withLock { cachedDepartment } ?: me().department?.takeIf { it.isNotBlank() } ?: DEFAULT_DEPARTMENT

    /**
     * Built from `GET /v1/me` (department, role, class codes), the user's inquiries and [seniorEngineers].
     * Not on the backend yet: notification prefs (in memory, defaults on).
     */
    override suspend fun profile(): StaffProfile {
        val me = me()
        val mine = remoteInquiries()
        val done = mine.filter { it.status == InquiryStatus.Done }
        val (whenDone, whenJoins) = lock.withLock { notifyWhenDone to notifyWhenStudentJoins }
        return StaffProfile(
            department = me.department.orEmpty(),
            title = when (me.role.lowercase()) {
                "admin" -> "Administrator"
                else -> "Staff"
            },
            classes = me.classes,
            projectsPosted = mine.size,
            delivered = done.size,
            raisedUsd = done.sumOf { it.draft.priceUsd ?: 0 },
            seniorEngineers = seniorEngineers(),
            notifyWhenDone = whenDone,
            notifyWhenStudentJoins = whenJoins,
        )
    }

    /**
     * PROPOSED `GET /v1/senior-engineers` →
     * `{"items":[{"id","name","title","bio","avatar_url","skills":[..],"github_username","email","roles":[..]}]}`
     * (a bare array is accepted; parsed leniently, see `SeniorEngineerDtos.kt`). `roles` containing `interviewer`
     * or `lead` marks an interviewer; an explicit `is_interviewer` wins.
     *
     * Best effort, never fails the profile: 404 (route not shipped) → empty list (the Profile hides the section);
     * any other error → the last list we got (empty on first load).
     */
    private suspend fun seniorEngineers(): List<SeniorEngineer> {
        val list = try {
            parseSeniorEngineerList(json.parseToJsonElement(send(HttpMethod.Get, "v1/senior-engineers")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.status == 404) emptyList() else return lock.withLock { cachedEngineers }
        } catch (e: Exception) {
            return lock.withLock { cachedEngineers } // malformed JSON
        }
        lock.withLock { cachedEngineers = list }
        return list
    }

    /**
     * `GET /v1/users/{id}` (public profile: `id, name, initials, avatar, bio, role, department, skills, classes`;
     * no email, no title). Fields it lacks (title, email, GitHub, interviewer flag) come from the cached list item.
     * Errors propagate as [ApiException]; the UI keeps showing the list data.
     */
    override suspend fun seniorEngineer(id: String): SeniorEngineer {
        val cached = lock.withLock { cachedEngineers.firstOrNull { it.id == id } }
        val obj = json.parseToJsonElement(send(HttpMethod.Get, "v1/users/${id(id)}")) as? JsonObject
        // Some backends wrap single resources: {"user":{...}}.
        val body = (obj?.get("user") as? JsonObject) ?: obj
        return body?.toSeniorEngineer(fallback = cached) ?: cached
            ?: throw ApiException(404, "not_found", "That engineer does not exist.")
    }

    /** Backend gap: stored in memory only. Wanted `PATCH /v1/staff/profile/notifications`. */
    override suspend fun updateNotifications(notifyWhenDone: Boolean, notifyWhenStudentJoins: Boolean): StaffProfile {
        lock.withLock {
            this.notifyWhenDone = notifyWhenDone
            this.notifyWhenStudentJoins = notifyWhenStudentJoins
        }
        return profile()
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun decodeInquiry(text: String): StaffInquiry = json.decodeFromString(InquiryDto.serializer(), text).toStaffInquiry()

    private suspend fun localDraft(id: String): StaffInquiry? =
        if (isLocal(id)) lock.withLock { localDrafts.firstOrNull { it.id == id } } ?: throw notFound() else null

    private fun notFound() = ApiException(404, "not_found", "That inquiry does not exist.")

    internal companion object {
        const val LOCAL_DRAFT_PREFIX = "localdraft-"
        /** Students must be approved to join posted inquiries; the reviewer vets them. */
        const val JOINING = "approval"
        const val DEFAULT_DEPARTMENT = "BAU"

        fun isLocal(id: String) = id.startsWith(LOCAL_DRAFT_PREFIX)

        /** `bounty` is the cash reward (0..500 USD); non-cash rewards send 0. */
        fun bountyOf(draft: InquiryDraft): Int =
            if (RewardType.Price in draft.rewards) draft.priceUsd ?: 0 else 0

        fun reviewerTypeOf(draft: InquiryDraft): String =
            if (draft.projectFor == ProjectFor.ClassProject) "professor" else "ai_then_lead"

        fun computeOverview(list: List<StaffInquiry>): StaffOverview = StaffOverview(
            raisedUsd = list.filter { it.status == InquiryStatus.Done }.sumOf { it.draft.priceUsd ?: 0 },
            wastedUsd = list.filter { it.status == InquiryStatus.Cancelled }.sumOf { it.draft.priceUsd ?: 0 },
            openCount = list.count { it.status.isOpen && it.status != InquiryStatus.InProgress },
            inProgressCount = list.count { it.status == InquiryStatus.InProgress },
            deliveredCount = list.count { it.status == InquiryStatus.Done },
            needsAttention = list.firstOrNull { it.progress?.blocker?.resolved == false },
        )
    }
}
