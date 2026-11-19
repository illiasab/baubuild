package com.vesper.baubuild.data.fake

import com.vesper.baubuild.core.DataChanges
import com.vesper.baubuild.core.model.*
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.repository.StaffRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Sample staff data mirroring https://baybuild.vercel.app/ ("iOS · Staff" / "Portal · Staff"). */
object FakeStaffData {
    /** The demo staff account with a full history. Any other @bau.edu account starts empty (shows world projects). */
    const val DEMO_EMAIL = "nora.hale@bau.edu"

    val labBooking = StaffInquiry(
        id = "si_lab_booking",
        draft = InquiryDraft(
            title = "lab booking app",
            explanation = "students keep fighting over lab equipment, they book it on paper. need an app for it, ios and android",
            expectation = "see what is free and book it. staff can cancel",
            types = setOf(ProjectType.IosApp, ProjectType.AndroidApp),
            projectFor = ProjectFor.ClassProject, classCode = "CMPS433",
            rewards = setOf(RewardType.Price), priceUsd = 40, deadline = "2026-12-03",
        ),
        status = InquiryStatus.AiRefining,
        refined = RefinedInquiry(
            title = "Build a lab equipment booking app",
            brief = "Students book lab gear on paper and often clash. Build an iOS and Android app that shows what is free and lets students reserve it for a time slot.",
            doneLooksLike = listOf(
                "Students see live availability",
                "Two bookings can never overlap",
                "Lab staff can approve or cancel",
            ),
            types = setOf(ProjectType.IosApp, ProjectType.AndroidApp),
            difficulty = Difficulty.Medium, minScore = 150, rewardSummary = "$40", deadline = "2026-12-03",
            whatAiChanged = "Rewrote the title and brief in plain language, added three acceptance checks, and suggested Medium difficulty with a 150+ score.",
            notifiesOnDone = "Ilias and Arian, the senior engineers",
        ),
        latest = "Waiting for your check", rewardLabel = "$40",
    )

    val shuttle = StaffInquiry(
        id = "si_shuttle",
        draft = InquiryDraft(
            title = "Shuttle timetable widget",
            explanation = "Students miss shuttles because the timetable is a PDF. Build a widget for the student portal and app that shows the next departures.",
            expectation = "Live times for every route, a countdown to the next departure, works on phone and web.",
            types = setOf(ProjectType.IosApp, ProjectType.AndroidApp, ProjectType.Website),
            projectFor = ProjectFor.BauProject, rewards = setOf(RewardType.Price), priceUsd = 35,
            priority = InquiryPriority.Urgent, deadline = "2026-11-22",
        ),
        status = InquiryStatus.InProgress,
        progress = InquiryProgress(
            today = "Today · Nov 19", dayOf = 4, totalDays = 7, percentDone = 60,
            doneToday = listOf("Live times load for all 4 routes", "Next-departure countdown added"),
            blocker = InquiryBlocker(
                id = "b1", text = "Waiting on IT for the shuttle GPS API key. Live positions are mocked until it arrives.",
                raisedBy = "Sam C.", raisedAt = "10:42",
            ),
            log = listOf(
                InquiryLogEntry("Nov 18", "Route list and stop pages built. 3 commits approved by the dev lead."),
                InquiryLogEntry("Nov 17", "Repo set up and 3 students joined: Sam C., Lena K., Omar R."),
                InquiryLogEntry("Nov 16", "Posted after AI refinement."),
            ),
            studentNames = listOf("Sam C.", "Lena K.", "Omar R."),
        ),
        latest = "Day 4 of 7 · 1 blocker", rewardLabel = "$35", reviewedBy = "dev lead",
    )

    val courseSearch = StaffInquiry(
        id = "si_course_search",
        draft = InquiryDraft(
            title = "Rebuild course-search ranking",
            explanation = "Course search ranks by course number. Results should rank by relevance.",
            expectation = "Exact code matches first, then title and description matches.",
            types = setOf(ProjectType.Website), projectFor = ProjectFor.ClassProject, classCode = "CMPS433",
            rewards = setOf(RewardType.Price, RewardType.Ssn), priceUsd = 60, deadline = "2026-12-03",
            difficulty = Difficulty.Hard,
        ),
        status = InquiryStatus.Posted, latest = "2 students asked to join", rewardLabel = "$60 + SSN",
        reviewedBy = "professor",
    )

    val roomBooking = StaffInquiry(
        id = "si_room_booking",
        draft = InquiryDraft(
            title = "Fix room-booking validation",
            explanation = "The library booking form accepts dates in the past.",
            expectation = "Past dates are rejected and a test covers it.",
            types = setOf(ProjectType.Website), projectFor = ProjectFor.BauProject, priceUsd = 18,
        ),
        status = InquiryStatus.Done, latest = "Ilias & Arian notified", rewardLabel = "$18",
    )

    val attendance = StaffInquiry(
        id = "si_attendance",
        draft = InquiryDraft(
            title = "Club attendance export",
            explanation = "Staff copy attendance by hand each week.",
            expectation = "A script that exports attendance to CSV.",
            types = setOf(ProjectType.DataScript), projectFor = ProjectFor.BauProject, priceUsd = 20,
        ),
        status = InquiryStatus.Cancelled, latest = "$20 budget wasted", rewardLabel = "$20",
    )

    val inquiries = listOf(labBooking, shuttle, courseSearch, roomBooking, attendance)

    val worldProjects = listOf(
        WorldProject("w1", "Flood early-warning dashboard", "Turn open river-gauge data into alerts a city can act on.",
            "Open data initiative", setOf(ProjectType.WebApp, ProjectType.DataScript)),
        WorldProject("w2", "Accessible campus map", "An indoor map with step-free routes for wheelchair users.",
            "University accessibility offices", setOf(ProjectType.IosApp, ProjectType.AndroidApp)),
        WorldProject("w3", "Food bank stock tracker", "Track donations and expiry dates so less food is thrown away.",
            "Local food banks", setOf(ProjectType.WebApp, ProjectType.BackendApi)),
        WorldProject("w4", "Air-quality sensor network", "Collect and chart readings from low-cost sensors across campus.",
            "Environmental research groups", setOf(ProjectType.DataScript, ProjectType.Website)),
    )

    val ilias = SeniorEngineer(
        id = "eng-ilias", name = "Ilias", title = "Tech Lead Engineer",
        bio = "Ilias leads engineering at BAU BUILD. He reviews student code before it ships, keeps the architecture " +
            "honest and runs the Lead interviews — if you reach Lead, you meet Ilias in person.",
        skills = listOf("Kotlin", "Swift", "Go", "System design"),
        githubUsername = "illiasab", email = "ilias@bau.edu", isInterviewer = true,
    )

    val arian = SeniorEngineer(
        id = "eng-arian", name = "Arian", title = "Senior Engineer",
        bio = "Arian mentors student teams through their first real projects, does code review on every delivery " +
            "and sits on the Lead interviews.",
        skills = listOf("TypeScript", "React", "Node.js", "Cloud"),
        // GitHub handle unknown for the demo; the backend sends the real one.
        githubUsername = null, email = "arian@bau.edu", isInterviewer = true,
    )

    val seniorEngineers = listOf(ilias, arian)

    val noraProfile = StaffProfile(
        department = "Computer Science", title = "Professor", classes = listOf("CMPS433", "CMPS210"),
        projectsPosted = 12, delivered = 9, raisedUsd = 4_820, seniorEngineers = seniorEngineers,
    )
}

/**
 * In-memory staff backend for the signed-in staff user. The demo account ([FakeStaffData.DEMO_EMAIL]) has the
 * design's history; other staff start with nothing (Overview then shows world projects). AI refinement waits a
 * couple of seconds to feel real.
 */
class FakeStaffRepository(private val session: SessionStore) : StaffRepository {
    private val byUser = mutableMapOf<String, MutableStateFlow<List<StaffInquiry>>>()
    private val profiles = mutableMapOf<String, StaffProfile>()
    private var nextId = 1

    private fun email() = (session.state.value as? SessionState.SignedIn)?.user?.email?.lowercase() ?: ""
    private fun store() = byUser.getOrPut(email()) {
        MutableStateFlow(if (email() == FakeStaffData.DEMO_EMAIL) FakeStaffData.inquiries else emptyList())
    }
    private fun replace(updated: StaffInquiry): StaffInquiry {
        store().value = store().value.map { if (it.id == updated.id) updated else it }
        DataChanges.inquiriesChanged()
        return updated
    }

    override suspend fun overview(): StaffOverview {
        delay(300)
        val list = store().value
        val demo = email() == FakeStaffData.DEMO_EMAIL
        return StaffOverview(
            raisedUsd = if (demo) 4_820 else list.filter { it.status == InquiryStatus.Done }.sumOf { it.draft.priceUsd ?: 0 },
            wastedUsd = if (demo) 310 else list.filter { it.status == InquiryStatus.Cancelled }.sumOf { it.draft.priceUsd ?: 0 },
            openCount = list.count { it.status.isOpen && it.status != InquiryStatus.InProgress },
            inProgressCount = list.count { it.status == InquiryStatus.InProgress },
            deliveredCount = if (demo) 9 else list.count { it.status == InquiryStatus.Done },
            needsAttention = list.firstOrNull { it.progress?.blocker?.resolved == false },
        )
    }

    override suspend fun inquiries(): List<StaffInquiry> { delay(300); return store().value }

    override suspend fun inquiry(id: String): StaffInquiry { delay(200); return store().value.first { it.id == id } }

    override suspend fun refine(draft: InquiryDraft): RefinedInquiry {
        delay(2_200) // AI takes a moment
        val title = draft.title.trim().replaceFirstChar { it.uppercase() }
        val types = draft.types.ifEmpty { setOf(ProjectType.Other) }
        val difficulty = draft.difficulty ?: when {
            types.size >= 3 || draft.priceUsd ?: 0 >= 50 -> Difficulty.Hard
            types.size == 2 -> Difficulty.Medium
            else -> Difficulty.Easy
        }
        return RefinedInquiry(
            title = if (title.startsWith("Build", ignoreCase = true)) title else "Build $title".trim(),
            brief = draft.explanation.trim().replaceFirstChar { it.uppercase() }.let { if (it.endsWith('.')) it else "$it." } +
                (if (draft.expectation.isNotBlank()) " ${draft.expectation.trim().replaceFirstChar { it.uppercase() }}." else ""),
            doneLooksLike = draft.expectation.split('.', ',', '\n').map { it.trim() }.filter { it.length > 3 }
                .map { it.replaceFirstChar { c -> c.uppercase() } }.ifEmpty { listOf("The brief is delivered and reviewed by a dev lead") }
                .take(4),
            types = types, difficulty = difficulty,
            minScore = when (difficulty) { Difficulty.Easy -> 0; Difficulty.Medium -> 150; Difficulty.Hard -> 300 },
            rewardSummary = rewardLabel(draft), deadline = draft.deadline,
            whatAiChanged = "Rewrote the title and brief in plain language, turned your expectations into acceptance checks, " +
                "and suggested ${difficulty.name} difficulty.",
            notifiesOnDone = "Ilias and Arian, the senior engineers",
        )
    }

    override suspend fun post(draft: InquiryDraft, refined: RefinedInquiry): StaffInquiry {
        delay(500)
        val posted = StaffInquiry(
            id = "si_new_${nextId++}", draft = draft, status = InquiryStatus.Posted, refined = refined,
            latest = "Just posted", rewardLabel = refined.rewardSummary,
        )
        store().value = listOf(posted) + store().value
        DataChanges.inquiriesChanged()
        return posted
    }

    override suspend fun saveDraft(draft: InquiryDraft): StaffInquiry {
        delay(300)
        val saved = StaffInquiry(id = "si_draft_${nextId++}", draft = draft, status = InquiryStatus.Draft,
            latest = "Draft", rewardLabel = rewardLabel(draft))
        store().value = listOf(saved) + store().value
        DataChanges.inquiriesChanged()
        return saved
    }

    override suspend fun updateDetails(id: String, draft: InquiryDraft): StaffInquiry {
        delay(300)
        return replace(store().value.first { it.id == id }.copy(draft = draft, rewardLabel = rewardLabel(draft)))
    }

    override suspend fun cancel(id: String): StaffInquiry {
        delay(300)
        val current = store().value.first { it.id == id }
        return replace(current.copy(status = InquiryStatus.Cancelled, latest = "${current.rewardLabel} budget wasted"))
    }

    override suspend fun resolveBlocker(inquiryId: String, blockerId: String): StaffInquiry {
        delay(300)
        val current = store().value.first { it.id == inquiryId }
        val progress = current.progress ?: return current
        return replace(current.copy(progress = progress.copy(blocker = progress.blocker?.copy(resolved = true)),
            latest = "Day ${progress.dayOf} of ${progress.totalDays} · no blockers"))
    }

    override suspend fun worldProjects(): List<WorldProject> { delay(300); return FakeStaffData.worldProjects }

    override suspend fun profile(): StaffProfile {
        delay(200)
        return profiles.getOrPut(email()) {
            if (email() == FakeStaffData.DEMO_EMAIL) FakeStaffData.noraProfile
            else StaffProfile("Student Affairs", "Administrator", emptyList(), 0, 0, 0, FakeStaffData.seniorEngineers)
        }
    }

    override suspend fun updateNotifications(notifyWhenDone: Boolean, notifyWhenStudentJoins: Boolean): StaffProfile {
        delay(200)
        return profile().copy(notifyWhenDone = notifyWhenDone, notifyWhenStudentJoins = notifyWhenStudentJoins)
            .also { profiles[email()] = it }
    }

    override suspend fun seniorEngineer(id: String): SeniorEngineer {
        delay(250)
        return FakeStaffData.seniorEngineers.firstOrNull { it.id == id } ?: error("No senior engineer $id")
    }

    private fun rewardLabel(d: InquiryDraft): String = buildList {
        if (RewardType.Price in d.rewards && d.priceUsd != null) add("$${d.priceUsd}")
        if (RewardType.Ssn in d.rewards) add("SSN")
        if (RewardType.JobAtBau in d.rewards) add("Job at BAU")
        if (RewardType.SchoolDiscount in d.rewards) add("${d.schoolDiscountPercent ?: 0}% off")
        if (RewardType.Volunteer in d.rewards) add("Volunteer")
    }.joinToString(" + ").ifEmpty { "—" }
}
