package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.DataChanges
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.NewInquiry
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.http.student.dto.InquiryDto
import com.vesper.baubuild.data.http.student.dto.MyWorkDto
import com.vesper.baubuild.data.http.student.dto.NewInquiryDto
import com.vesper.baubuild.data.http.student.dto.PageDto
import com.vesper.baubuild.data.http.student.dto.UrgentCardDto
import com.vesper.baubuild.data.http.student.dto.Wire
import com.vesper.baubuild.data.repository.InquiryRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Student side of [InquiryRepository].
 *
 * - [urgentInquiries]: `GET /v1/inquiries/urgent` → urgent *published projects* (the card id is the project id,
 *   so [Inquiry.projectId] = id). Projects the user already joined / requested are dropped using `GET /v1/me/work`
 *   (best effort: if that call fails the list is shown unfiltered).
 * - [submit]: `POST /v1/inquiries` (staff only → 403 `role_required` otherwise). The backend requires difficulty,
 *   min score, joining and reviewer, which [NewInquiry] doesn't carry; [defaults] fills them.
 */
class HttpInquiryRepository(
    private val client: HttpClient,
    private val defaults: InquiryDefaults = InquiryDefaults(),
) : InquiryRepository {

    data class InquiryDefaults(
        val difficulty: Difficulty = Difficulty.Easy,
        val minScore: Int = 0,
        /** `everyone` | `approval`. */
        val joining: String = "everyone",
        /** `ai_then_lead` | `professor`. */
        val reviewerType: String = "ai_then_lead",
    )

    override suspend fun urgentInquiries(): List<Inquiry> = coroutineScope {
        val mine = async { myProjectIds() }
        val urgent: PageDto<UrgentCardDto> = apiCall { client.get("v1/inquiries/urgent").body() }
        val skip = mine.await()
        urgent.items.filter { it.id !in skip }.map { it.toDomain() }
    }

    override suspend fun submit(inquiry: NewInquiry): Inquiry {
        val body = NewInquiryDto(
            title = inquiry.title.trim(),
            description = inquiry.description.trim(),
            department = inquiry.department.trim(),
            difficulty = Wire.difficulty(defaults.difficulty),
            bounty = (inquiry.suggestedBountyUsd ?: 0).coerceIn(0, MAX_BOUNTY),
            isUrgent = inquiry.isUrgent,
            minScore = defaults.minScore,
            joining = defaults.joining,
            reviewerType = defaults.reviewerType,
        )
        val created: InquiryDto = apiCall {
            client.post("v1/inquiries") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
        }
        DataChanges.inquiriesChanged()
        return created.toDomain(requestedBy = "You")
    }

    private suspend fun myProjectIds(): Set<String> = try {
        val work: MyWorkDto = apiCall { client.get("v1/me/work").body() }
        (work.inProgress.map { it.id } + work.awaitingApproval.map { it.id }).toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptySet()
    }

    private companion object {
        /** Backend limit for `bounty`. */
        const val MAX_BOUNTY = 500
    }
}
