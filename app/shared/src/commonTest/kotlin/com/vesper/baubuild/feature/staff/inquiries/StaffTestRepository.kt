package com.vesper.baubuild.feature.staff.inquiries

import com.vesper.baubuild.core.model.InquiryDraft
import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.RefinedInquiry
import com.vesper.baubuild.core.model.SeniorEngineer
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.core.model.StaffOverview
import com.vesper.baubuild.core.model.StaffProfile
import com.vesper.baubuild.core.model.WorldProject
import com.vesper.baubuild.data.fake.FakeStaffData
import com.vesper.baubuild.data.repository.StaffRepository

/** In-memory staff backend for ViewModel tests, with switches to fail writes. Does not emit DataChanges. */
class StaffTestRepository(initial: List<StaffInquiry> = FakeStaffData.inquiries) : StaffRepository {
    var inquiries: List<StaffInquiry> = initial
    var failReads = false
    var failWrites = false
    var profileTitle = "Professor"
    val updates = mutableListOf<Pair<String, InquiryDraft>>()
    var cancelCalls = 0
    var resolveCalls = 0

    private fun readCheck() { if (failReads) error("offline") }
    private fun writeCheck() { if (failWrites) error("offline") }
    private fun replace(updated: StaffInquiry): StaffInquiry {
        inquiries = inquiries.map { if (it.id == updated.id) updated else it }
        return updated
    }

    override suspend fun overview(): StaffOverview {
        readCheck()
        return StaffOverview(
            raisedUsd = 4_820, wastedUsd = 310,
            openCount = inquiries.count { it.status.isOpen && it.status != InquiryStatus.InProgress },
            inProgressCount = inquiries.count { it.status == InquiryStatus.InProgress },
            deliveredCount = inquiries.count { it.status == InquiryStatus.Done },
            needsAttention = inquiries.firstOrNull { it.progress?.blocker?.resolved == false },
        )
    }

    override suspend fun inquiries(): List<StaffInquiry> { readCheck(); return inquiries }
    override suspend fun inquiry(id: String): StaffInquiry { readCheck(); return inquiries.first { it.id == id } }
    override suspend fun refine(draft: InquiryDraft): RefinedInquiry = error("unused")
    override suspend fun post(draft: InquiryDraft, refined: RefinedInquiry): StaffInquiry = error("unused")
    override suspend fun saveDraft(draft: InquiryDraft): StaffInquiry = error("unused")

    override suspend fun updateDetails(id: String, draft: InquiryDraft): StaffInquiry {
        writeCheck()
        updates += id to draft
        return replace(inquiries.first { it.id == id }.copy(draft = draft))
    }

    override suspend fun cancel(id: String): StaffInquiry {
        writeCheck()
        cancelCalls++
        val current = inquiries.first { it.id == id }
        return replace(current.copy(status = InquiryStatus.Cancelled, latest = "${current.rewardLabel} budget wasted"))
    }

    override suspend fun resolveBlocker(inquiryId: String, blockerId: String): StaffInquiry {
        writeCheck()
        resolveCalls++
        val current = inquiries.first { it.id == inquiryId }
        val progress = current.progress!!
        return replace(current.copy(progress = progress.copy(blocker = progress.blocker?.copy(resolved = true))))
    }

    override suspend fun worldProjects(): List<WorldProject> { readCheck(); return FakeStaffData.worldProjects }

    override suspend fun seniorEngineer(id: String): SeniorEngineer = FakeStaffData.seniorEngineers.first { it.id == id }

    override suspend fun profile(): StaffProfile = FakeStaffData.noraProfile.copy(title = profileTitle)

    override suspend fun updateNotifications(notifyWhenDone: Boolean, notifyWhenStudentJoins: Boolean): StaffProfile =
        profile()
}
