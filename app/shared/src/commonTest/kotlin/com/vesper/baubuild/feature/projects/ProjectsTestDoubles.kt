package com.vesper.baubuild.feature.projects

import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.BauClass
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.Membership
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.NewInquiry
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.repository.ClassRepository
import com.vesper.baubuild.data.repository.InquiryRepository
import com.vesper.baubuild.data.repository.ProjectRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

internal fun signedIn(user: User = FakeData.sam) = MutableStateFlow<SessionState>(SessionState.SignedIn(user))

internal val staff = User(
    id = "u_staff", displayName = "Dana Kaya", email = "dana.kaya@bau.edu",
    kind = AccountKind.BauStaff, role = Role.Staff,
)

/** Programmable project repository: flip [fail] to make the next calls throw. */
internal class TestProjectRepository(
    var projects: List<Project> = FakeData.projects,
) : ProjectRepository {
    var fail = false
    var openCalls = 0
    var joinCalls = 0
    /** When set, join() suspends until completed (to observe the in-progress state). */
    var joinGate: CompletableDeferred<Unit>? = null

    private fun check() { if (fail) error("offline") }

    override suspend fun openProjects(difficulty: Difficulty?): List<Project> {
        openCalls++
        check()
        return projects.filter { difficulty == null || it.difficulty == difficulty }
    }

    override suspend fun project(id: String): Project { check(); return projects.first { it.id == id } }

    override suspend fun projectsForClass(classCode: String): List<Project> {
        check()
        return projects.filter { it.classCode == classCode }
    }

    override suspend fun join(projectId: String): Membership {
        joinCalls++
        joinGate?.await()
        check()
        val p = projects.first { it.id == projectId }
        return Membership(p, if (p.joinPolicy == JoinPolicy.Open) MembershipStatus.In else MembershipStatus.WaitingForApproval)
    }
}

internal class TestInquiryRepository(
    var urgent: List<Inquiry> = FakeData.inquiries,
) : InquiryRepository {
    var fail = false
    val submitted = mutableListOf<NewInquiry>()

    override suspend fun urgentInquiries(): List<Inquiry> { if (fail) error("offline"); return urgent }

    override suspend fun submit(inquiry: NewInquiry): Inquiry {
        if (fail) error("offline")
        submitted += inquiry
        return Inquiry("i_new", inquiry.title, "You", inquiry.department, inquiry.suggestedBountyUsd ?: 0, Difficulty.Easy, inquiry.isUrgent)
    }
}

internal class TestClassRepository(var classes: List<BauClass> = FakeData.classes) : ClassRepository {
    var fail = false
    override suspend fun myClasses(): List<BauClass> { if (fail) error("offline"); return classes }
}
