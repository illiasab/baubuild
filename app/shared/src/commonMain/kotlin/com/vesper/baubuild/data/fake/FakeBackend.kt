package com.vesper.baubuild.data.fake

import com.vesper.baubuild.core.model.*
import com.vesper.baubuild.data.repository.*
import kotlinx.coroutines.delay

/** Sample data mirroring https://baybuild.vercel.app/ (iOS app tab). Used until the Go backend is live. */
object FakeData {
    val sam = User(
        id = "u_sam", displayName = "Sam Carter", email = "sam.carter@stu.bau.edu",
        kind = AccountKind.BauStudent, role = Role.Student,
        bio = "Computer Science student. Learning by shipping real work.",
        score = 180, earningsUsd = 38, approvedProjects = 2,
        skills = listOf("JavaScript", "Python"), classCodes = listOf("CMPS433"),
    )

    val roomBooking = Project(
        id = "p_room_booking", title = "Fix form validation on the room-booking page", owner = "Library",
        summary = "Library · everyone welcome",
        description = "The library’s booking form accepts dates in the past. Find the check, fix it, and add a test so it stays fixed.",
        bountyUsd = 18, difficulty = Difficulty.Easy, minScore = 0, joinPolicy = JoinPolicy.Open, dueInDays = 3,
        repoName = "library-booking", repoConnected = true, reviewer = Reviewer.AiThenDevLead, taskCount = 3,
    )
    val attendanceCsv = Project(
        id = "p_attendance_csv", title = "Export club attendance to CSV", owner = "Student Affairs",
        summary = "Student Affairs · everyone welcome",
        description = "Club leaders need attendance as a CSV. Add an export button to the attendance page.",
        bountyUsd = 15, difficulty = Difficulty.Easy, minScore = 0, joinPolicy = JoinPolicy.Open, dueInDays = 5,
        repoName = "student-affairs-portal", repoConnected = true, taskCount = 2,
    )
    val courseSearch = Project(
        id = "p_course_search", title = "Rebuild course-search ranking", owner = "CMPS433",
        summary = "CMPS433 · professor approves",
        description = "Course search ranks by title only. Rank by code, title and description, and boost exact code matches.",
        bountyUsd = 60, difficulty = Difficulty.Hard, minScore = 300, joinPolicy = JoinPolicy.ApprovalRequired,
        dueInDays = 14, repoName = "course-search", repoConnected = true, reviewer = Reviewer.Professor,
        classCode = "CMPS433", taskCount = 5,
    )
    val shuttleWidget = Project(
        id = "p_shuttle", title = "Shuttle timetable widget", owner = "Transport",
        summary = "Transport · any score welcome",
        description = "Show the next three campus shuttles on the home screen widget.",
        bountyUsd = 35, difficulty = Difficulty.Hard, minScore = 0, joinPolicy = JoinPolicy.Open, dueInDays = 4,
        repoName = "shuttle-widget", repoConnected = true, reviewer = Reviewer.DevLead, taskCount = 4,
    )
    val projects = listOf(roomBooking, attendanceCsv, courseSearch, shuttleWidget)

    val inquiries = listOf(
        Inquiry("i_shuttle", "Shuttle timetable widget", "Transport Office", "Transport", 35, Difficulty.Hard, true, 0, shuttleWidget.id),
        Inquiry("i_wifi", "Wi-Fi outage status page", "IT Services", "IT", 25, Difficulty.Easy, true, 0, null),
    )

    val classes = listOf(
        BauClass("CMPS433", "Information Retrieval", "Prof. Aydın", "Fall 2026", 1),
        BauClass("CMPS350", "Web Development", "Prof. Demir", "Fall 2026", 2),
    )

    val memberships = listOf(
        Membership(roomBooking, MembershipStatus.In),
        Membership(attendanceCsv, MembershipStatus.In),
        Membership(courseSearch, MembershipStatus.InReview, reviewingBy = "professor of CMPS433"),
        Membership(shuttleWidget, MembershipStatus.InReview, reviewingBy = "dev lead"),
    )

    fun workspace(projectId: String) = Workspace(
        project = projects.first { it.id == projectId },
        tasks = listOf(
            ProjectTask("t1", "Reject past dates in the booking form", TaskStatus.InProgress, dueInDays = 3),
            ProjectTask("t2", "Add a test for the date check", TaskStatus.ToDo),
            ProjectTask("t3", "Announce the error to screen readers", TaskStatus.ToDo),
        ),
        changes = listOf(
            CodeChange("c1", "Reject dates before today", "fix-date-validation", ChangeStatus.Approved),
            CodeChange("c2", "Add test for past-date check", "fix-date-validation", ChangeStatus.WithDevLead),
            CodeChange("c3", "Show inline error message", "fix-date-validation", ChangeStatus.ChangesRequested),
        ),
    )

    val reviews = listOf(
        ReviewStatus(
            project = roomBooking, headline = "Your fix is with the dev lead.", branch = "fix-date-validation",
            stages = listOf(
                ReviewStage("Pushed to GitHub", "fix-date-validation", ReviewStageState.Done),
                ReviewStage("AI Review Complete", "Past dates are rejected, and a test covers it.", ReviewStageState.Done,
                    note = "Worth a look: the error is not announced to screen readers."),
                ReviewStage("Dev Lead Is Deciding", "A vetted dev lead is reading the AI summary and your diff.", ReviewStageState.Active),
                ReviewStage("Bounty and Record", "\$18 is paid and the project joins your verified record once approved.", ReviewStageState.Pending),
            ),
            diffUrl = "https://github.com/bau-build/library-booking/compare/fix-date-validation",
        ),
    )
}

private suspend fun latency() = delay(350)

/**
 * Understands codes from [com.vesper.baubuild.core.auth.FakeMicrosoftSignIn] (`fake-code:<email>`) and applies
 * [AccountPolicy] like the real backend: BAU students/staff get a session, everyone else is refused.
 */
class FakeAuthRepository(private val policy: AccountPolicy) : AuthRepository {
    override suspend fun exchangeMicrosoftCode(code: String, codeVerifier: String, redirectUri: String): AuthExchangeResult {
        latency()
        val email = code.removePrefix(FAKE_CODE_PREFIX).takeIf { code.startsWith(FAKE_CODE_PREFIX) } ?: FakeData.sam.email
        return when (val access = policy.access(email)) {
            AccountPolicy.Access.Free -> AuthExchangeResult.SignedIn(AuthSession(FAKE_TOKEN_PREFIX + email, userFor(email)))
            else -> AuthExchangeResult.NotAllowed(email, access)
        }
    }

    override suspend fun me(token: String): User {
        latency()
        val email = token.removePrefix(FAKE_TOKEN_PREFIX).takeIf { token.startsWith(FAKE_TOKEN_PREFIX) } ?: FakeData.sam.email
        if (policy.access(email) != AccountPolicy.Access.Free) error("Session no longer valid")
        return userFor(email)
    }

    override suspend fun signOut(token: String) = latency()

    private fun userFor(email: String): User = when {
        email.equals(FakeData.sam.email, ignoreCase = true) -> FakeData.sam
        else -> {
            val kind = policy.classify(email)
            val name = email.substringBefore('@').split('.', '_', '-').filter { it.isNotBlank() }
                .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            User(
                id = "u_" + email.substringBefore('@'), displayName = name, email = email, kind = kind,
                role = if (kind == AccountKind.BauStaff) Role.Staff else Role.Student,
            )
        }
    }

    private companion object {
        const val FAKE_CODE_PREFIX = "fake-code:"
        const val FAKE_TOKEN_PREFIX = "fake-opaque-token:"
    }
}

class FakeProjectRepository : ProjectRepository {
    override suspend fun openProjects(difficulty: Difficulty?) =
        FakeData.projects.filter { difficulty == null || it.difficulty == difficulty }.also { latency() }
    override suspend fun project(id: String) = FakeData.projects.first { it.id == id }.also { latency() }
    override suspend fun projectsForClass(classCode: String) =
        FakeData.projects.filter { it.classCode == classCode }.also { latency() }
    override suspend fun join(projectId: String): Membership {
        latency()
        val p = FakeData.projects.first { it.id == projectId }
        val status = if (p.joinPolicy == JoinPolicy.Open) MembershipStatus.In else MembershipStatus.WaitingForApproval
        return Membership(p, status)
    }
}

class FakeInquiryRepository : InquiryRepository {
    override suspend fun urgentInquiries() = FakeData.inquiries.filter { it.isUrgent }.also { latency() }
    override suspend fun submit(inquiry: NewInquiry): Inquiry {
        latency()
        return Inquiry("i_new", inquiry.title, "You", inquiry.department, inquiry.suggestedBountyUsd ?: 0,
            Difficulty.Easy, inquiry.isUrgent)
    }
}

class FakeClassRepository : ClassRepository {
    override suspend fun myClasses() = FakeData.classes.also { latency() }
}

class FakeWorkRepository : WorkRepository {
    override suspend fun myWork() = FakeData.memberships.also { latency() }
    override suspend fun workspace(projectId: String) = FakeData.workspace(projectId).also { latency() }
    override suspend fun reviews() = FakeData.reviews.also { latency() }
}
