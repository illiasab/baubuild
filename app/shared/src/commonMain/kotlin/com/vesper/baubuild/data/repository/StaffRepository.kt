package com.vesper.baubuild.data.repository

import com.vesper.baubuild.core.model.InquiryDraft
import com.vesper.baubuild.core.model.RefinedInquiry
import com.vesper.baubuild.core.model.SeniorEngineer
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.core.model.StaffOverview
import com.vesper.baubuild.core.model.StaffProfile
import com.vesper.baubuild.core.model.WorldProject

/**
 * Staff (@bau.edu) contract. Suggested endpoints (Go backend):
 * - `GET  /v1/staff/overview`                         → [StaffOverview]
 * - `GET  /v1/staff/inquiries`                        → all of the user's inquiries (open + closed), newest first
 * - `GET  /v1/staff/inquiries/{id}`                   → one, with progress
 * - `POST /v1/staff/inquiries/refine` {draft}         → [RefinedInquiry] (AI; may take several seconds)
 * - `POST /v1/staff/inquiries` {draft, refined}       → posts it; returns the [StaffInquiry] (status Posted)
 * - `POST /v1/staff/inquiries/drafts` {draft}         → saves a draft
 * - `PATCH /v1/staff/inquiries/{id}` {draft}          → edit details; the team sees changes right away
 * - `POST /v1/staff/inquiries/{id}/cancel`            → Cancelled (unspent budget counts as wasted)
 * - `POST /v1/staff/inquiries/{id}/blockers/{bid}/resolve`
 * - `GET  /v1/world-projects`                         → [WorldProject] ideas when the user has no inquiries
 * - `GET  /v1/senior-engineers`                      → `{items:[...]}` the senior engineers ([StaffProfile.seniorEngineers])
 * - `GET  /v1/users/{id}`                           → one senior engineer's public profile ([seniorEngineer])
 * - `GET  /v1/staff/profile`, `PATCH /v1/staff/profile/notifications` {notifyWhenDone, notifyWhenStudentJoins}
 */
interface StaffRepository {
    suspend fun overview(): StaffOverview
    suspend fun inquiries(): List<StaffInquiry>
    suspend fun inquiry(id: String): StaffInquiry
    suspend fun refine(draft: InquiryDraft): RefinedInquiry

    /**
     * False while [refine] is the client-side rewrite (no backend AI endpoint yet); the UI then labels the result
     * "Basic rewrite (AI refine not available yet)" instead of claiming AI did it.
     */
    val aiRefineAvailable: Boolean get() = true
    suspend fun post(draft: InquiryDraft, refined: RefinedInquiry): StaffInquiry
    suspend fun saveDraft(draft: InquiryDraft): StaffInquiry
    suspend fun updateDetails(id: String, draft: InquiryDraft): StaffInquiry
    suspend fun cancel(id: String): StaffInquiry
    suspend fun resolveBlocker(inquiryId: String, blockerId: String): StaffInquiry
    suspend fun worldProjects(): List<WorldProject>
    suspend fun profile(): StaffProfile
    suspend fun updateNotifications(notifyWhenDone: Boolean, notifyWhenStudentJoins: Boolean): StaffProfile
    /** Full bio of one senior engineer (id from [StaffProfile.seniorEngineers]). */
    suspend fun seniorEngineer(id: String): SeniorEngineer
}
