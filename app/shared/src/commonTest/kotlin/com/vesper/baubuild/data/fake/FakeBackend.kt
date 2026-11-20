package com.vesper.baubuild.data.fake

import com.vesper.baubuild.core.model.*
import com.vesper.baubuild.data.repository.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

/** Sample data mirroring https://baybuild.vercel.app/ (iOS app tab). Used until the Go backend is live. */
object FakeData {
    val sam = User(
        id = "u_sam", displayName = "Sam Carter", email = "sam.carter@stu.bau.edu",
        kind = AccountKind.BauStudent, role = Role.Student,
        bio = "Computer Science student. Learning by shipping real work.",
        // Demo account is a brand-new student: no score, earnings or approved work yet.
        score = 0, earningsUsd = 0, approvedProjects = 0,
        skills = emptyList(), classCodes = listOf("CMPS433"),
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
    // Extra projects shown on the Web portal design (desktop Projects grid).
    val eventsDarkMode = Project(
        id = "p_events_dark_mode", title = "Add dark mode to the events calendar", owner = "Events",
        summary = "Events · everyone welcome",
        description = "Add a theme switch that remembers the visitor’s choice.",
        bountyUsd = 20, difficulty = Difficulty.Medium, minScore = 0, joinPolicy = JoinPolicy.Open, dueInDays = 7,
        repoName = "events-calendar", repoConnected = true, taskCount = 3,
    )
    val faqLinks = Project(
        id = "p_faq_links", title = "Repair broken links on the FAQ", owner = "Admissions",
        summary = "Admissions · everyone welcome",
        description = "Find every dead link on the FAQ pages and point it somewhere real.",
        bountyUsd = 18, difficulty = Difficulty.Easy, minScore = 0, joinPolicy = JoinPolicy.Open, dueInDays = 4,
        repoName = "admissions-faq", repoConnected = true, taskCount = 2,
    )
    val transcriptForm = Project(
        id = "p_transcript_form", title = "Speed up the transcript request form", owner = "Registrar",
        summary = "Registrar · dev lead approves",
        description = "The form reloads on every field. Profile it and remove the reloads.",
        bountyUsd = 45, difficulty = Difficulty.Hard, minScore = 250, joinPolicy = JoinPolicy.ApprovalRequired,
        dueInDays = 10, repoName = "registrar-transcripts", repoConnected = true, reviewer = Reviewer.DevLead, taskCount = 4,
    )
    val projects = listOf(roomBooking, attendanceCsv, courseSearch, shuttleWidget, eventsDarkMode, faqLinks, transcriptForm)

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

    private val roomBookingTasks = listOf(
        ProjectTask(
            "t1", "Reject past dates in the booking form", TaskStatus.InProgress, dueInDays = 3, dueDate = "2026-11-22",
            priority = TaskPriority.High,
            description = "The booking form accepts dates before today. Validate the selected date on the client and " +
                "the server, and show an inline error next to the date field.",
            acceptanceCriteria = listOf(
                "Choosing a date before today shows “Pick today or a later date.” under the field",
                "The Book button stays disabled while the date is invalid",
                "The API returns 422 for a past date even if the client check is bypassed",
                "Today’s date is still accepted",
            ),
            resources = listOf(
                TaskResource("Booking form component", "https://github.com/bau-build/library-booking/blob/main/src/booking-form.js"),
                TaskResource("Date handling guide", "https://developer.mozilla.org/docs/Web/JavaScript/Reference/Global_Objects/Date"),
            ),
        ),
        ProjectTask(
            "t2", "Add a test for the date check", TaskStatus.ToDo, dueInDays = 4, dueDate = "2026-11-23",
            priority = TaskPriority.Medium,
            description = "Cover the new validation with unit tests so it stays fixed.",
            acceptanceCriteria = listOf(
                "A test fails if a past date is accepted",
                "A test passes for today and for a future date",
                "Tests run in CI with `npm test`",
            ),
        ),
        ProjectTask(
            "t3", "Announce the error to screen readers", TaskStatus.ToDo, dueInDays = 5, dueDate = "2026-11-24",
            priority = TaskPriority.Low,
            description = "The AI review noted the date error is not announced. Make it accessible.",
            acceptanceCriteria = listOf(
                "The error message is linked to the field with aria-describedby",
                "VoiceOver and TalkBack read the error when it appears",
            ),
        ),
    )

    /** Starter tasks for a project the user just joined (the backend creates these from the project's brief). */
    private fun starterTasks(p: Project) = listOf(
        ProjectTask(
            "${p.id}_t1", "Set up ${p.repoName ?: "the repository"} locally", TaskStatus.ToDo,
            dueInDays = 1, priority = TaskPriority.Medium,
            description = "Clone the repository, install dependencies and run the app and tests locally.",
            acceptanceCriteria = listOf("The app runs locally", "All existing tests pass", "You can push a branch"),
        ),
        ProjectTask(
            "${p.id}_t2", p.title, TaskStatus.ToDo, dueInDays = p.dueInDays, priority = TaskPriority.High,
            description = p.description,
            acceptanceCriteria = listOf(
                "The change does what the brief asks",
                "New or changed code is covered by tests",
                "The AI review has no blocking findings",
            ),
        ),
    )

    fun workspace(projectId: String): Workspace {
        val project = projects.first { it.id == projectId }
        val isSample = projectId == roomBooking.id
        return Workspace(
            project = project,
            tasks = if (isSample) roomBookingTasks else starterTasks(project),
            changes = if (!isSample) emptyList() else listOf(
                CodeChange("c1", "Reject dates before today", "fix-date-validation", ChangeStatus.Approved),
                CodeChange("c2", "Add test for past-date check", "fix-date-validation", ChangeStatus.WithDevLead),
                CodeChange("c3", "Show inline error message", "fix-date-validation", ChangeStatus.ChangesRequested),
            ),
        )
    }

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
 * In-memory stand-in for the backend's email + password accounts. Applies [AccountPolicy] like the real backend
 * (only BAU domains), keeps accounts and stores the session in [session] whenever "tokens" come back. Tests can force any outcome with the `next*` overrides and inspect [calls].
 */
class FakeAuthRepository(
    private val policy: AccountPolicy = AccountPolicy(),
    private val session: com.vesper.baubuild.core.session.SessionStore? = null,
    private val latencyMillis: Long = 0,
) : AuthRepository {
    data class Account(val name: String, val email: String, var password: String, var verified: Boolean)

    val accounts = mutableMapOf<String, Account>()
    val calls = mutableListOf<String>()

    var nextRegister: RegisterResult? = null
    var nextLogin: LoginResult? = null
    /** Thrown by the next call (e.g. a network failure). */
    var nextError: Exception? = null

    fun addAccount(email: String, password: String, verified: Boolean = true, name: String = "BAU Member") {
        accounts[email.lowercase()] = Account(name, email, password, verified)
    }

    private suspend fun step(name: String) {
        calls += name
        if (latencyMillis > 0) delay(latencyMillis)
        nextError?.let { nextError = null; throw it }
    }

    override suspend fun register(name: String, email: String, password: String): RegisterResult {
        step("register:$email")
        nextRegister?.let { nextRegister = null; return it }
        if (policy.access(email) != AccountPolicy.Access.Free) return AuthOutcome.DomainNotAllowed
        if (!com.vesper.baubuild.core.model.PasswordCheck.of(password).isValid) return AuthOutcome.WeakPassword
        val existing = accounts[email.lowercase()]
        if (existing?.verified == true) return AuthOutcome.EmailTaken
        accounts[email.lowercase()] = Account(name, email, password, verified = true)
        return signIn(email) // no email: registration signs in directly
    }

    override suspend fun login(email: String, password: String): LoginResult {
        step("login:$email")
        nextLogin?.let { nextLogin = null; return it }
        if (policy.access(email) != AccountPolicy.Access.Free) return AuthOutcome.DomainNotAllowed
        val account = accounts[email.lowercase()]
        if (account == null || account.password != password) return AuthOutcome.InvalidCredentials
        return signIn(account.email)
    }

    override suspend fun me(): User {
        step("me")
        val email = (session?.state?.value as? com.vesper.baubuild.core.session.SessionState.SignedIn)?.user?.email
            ?: FakeData.sam.email
        return userFor(email)
    }

    override suspend fun signOut() = step("signOut")

    private suspend fun signIn(email: String): AuthOutcome.SignedIn {
        val result = AuthOutcome.SignedIn(AuthSession(tokenFor(email), userFor(email)))
        session?.signedIn(result.session.tokens, result.session.user)
        return result
    }

    private fun userFor(email: String): User = when {
        email.equals(FakeData.sam.email, ignoreCase = true) -> FakeData.sam
        else -> {
            val kind = policy.classify(email)
            val name = accounts[email.lowercase()]?.name ?: email.substringBefore('@')
            User(
                id = "u_" + email.substringBefore('@'), displayName = name, email = email, kind = kind,
                role = if (kind == AccountKind.BauStaff) Role.Staff else Role.Student,
            )
        }
    }

    companion object {
        private const val FAKE_TOKEN_PREFIX = "fake-opaque-token:"

        /** The fake's opaque token for [email]. */
        fun tokenFor(email: String): String = FAKE_TOKEN_PREFIX + email
    }
}

/**
 * The demo user's project memberships for this app run (in memory). Joining adds here, so the project leaves the
 * Projects lists and shows up in My Work — mirroring what the backend does.
 */
object FakeMemberships {
    val list = kotlinx.coroutines.flow.MutableStateFlow(FakeData.memberships)
    val projectIds: Set<String> get() = list.value.mapTo(mutableSetOf()) { it.project.id }
}

class FakeProjectRepository : ProjectRepository {
    /** Projects the user is already in (or waiting on) are not "open" for them any more. */
    private fun available() = FakeData.projects.filter { it.id !in FakeMemberships.projectIds }

    override suspend fun openProjects(difficulty: Difficulty?) =
        available().filter { difficulty == null || it.difficulty == difficulty }.also { latency() }
    override suspend fun project(id: String) = FakeData.projects.first { it.id == id }.also { latency() }
    override suspend fun projectsForClass(classCode: String) =
        available().filter { it.classCode == classCode }.also { latency() }
    override suspend fun join(projectId: String): Membership {
        latency()
        FakeMemberships.list.value.firstOrNull { it.project.id == projectId }?.let { return it }
        val p = FakeData.projects.first { it.id == projectId }
        // Open projects let you straight in (no review); approval-required ones wait for a professor / dev lead.
        val status = if (p.joinPolicy == JoinPolicy.Open) MembershipStatus.In else MembershipStatus.WaitingForApproval
        val reviewingBy = when {
            status == MembershipStatus.In -> null
            p.reviewer == Reviewer.Professor -> "professor of ${p.classCode ?: p.owner}"
            else -> "dev lead"
        }
        return Membership(p, status, reviewingBy).also {
            FakeMemberships.list.value = listOf(it) + FakeMemberships.list.value
            com.vesper.baubuild.core.DataChanges.membershipsChanged()
        }
    }
}

class FakeInquiryRepository : InquiryRepository {
    override suspend fun urgentInquiries() = FakeData.inquiries
        .filter { it.isUrgent && (it.projectId == null || it.projectId !in FakeMemberships.projectIds) }
        .also { latency() }
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
    /** projectId → taskId → task as last written by [updateTaskStatus]. In memory only (lost on restart). */
    private val taskOverrides = mutableMapOf<String, MutableMap<String, ProjectTask>>()
    private val overridesLock = kotlinx.coroutines.sync.Mutex()

    override suspend fun myWork() = FakeMemberships.list.value.also { latency() }

    override suspend fun workspace(projectId: String): Workspace {
        latency()
        val base = FakeData.workspace(projectId)
        val overrides = overridesLock.withLock { taskOverrides[projectId]?.toMap().orEmpty() }
        return base.copy(tasks = base.tasks.map { overrides[it.id] ?: it })
    }

    override suspend fun reviews() = FakeData.reviews.also { latency() }

    override suspend fun updateTaskStatus(projectId: String, taskId: String, status: TaskStatus): ProjectTask {
        latency()
        return overridesLock.withLock {
            val overrides = taskOverrides.getOrPut(projectId) { mutableMapOf() }
            val current = overrides[taskId]
                ?: FakeData.workspace(projectId).tasks.firstOrNull { it.id == taskId }
                ?: throw NoSuchElementException("No task $taskId in project $projectId")
            current.copy(status = status).also { overrides[taskId] = it }
        }
    }
}
