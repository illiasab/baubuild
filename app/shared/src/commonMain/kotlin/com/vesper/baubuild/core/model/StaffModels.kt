package com.vesper.baubuild.core.model

import kotlinx.serialization.Serializable

// Staff (@bau.edu) side: staff post inquiries, AI refines them into projects, students build them.

@Serializable
enum class ProjectType(val label: String) {
    IosApp("iOS App"), AndroidApp("Android App"), Website("Website"), WebApp("Web App"),
    DesktopApp("Desktop App"), BackendApi("Backend / API"), DataScript("Data / Script"), Other("Other"),
}

@Serializable
enum class ProjectFor(val label: String) { ClassProject("Class Project"), BauProject("BAU Project") }

@Serializable
enum class RewardType(val label: String) {
    Price("Price"), Ssn("SSN"), JobAtBau("Job at BAU"), SchoolDiscount("% off School Expenses"), Volunteer("Volunteer"),
}

@Serializable
enum class InquiryPriority { Normal, Urgent }

/** Lifecycle of a staff inquiry. Open = AiRefining/ReadyToPost/Posted/InProgress; Closed = Done/Cancelled. */
@Serializable
enum class InquiryStatus(val label: String, val isOpen: Boolean) {
    Draft("Draft", true),
    AiRefining("AI Refining", true),
    ReadyToPost("Ready to Post", true),
    Posted("Posted", true),
    InProgress("In Progress", true),
    Done("Done", false),
    Cancelled("Cancelled", false),
}

/** What staff type in New Inquiry (steps 1 + 2). Also used to edit an existing inquiry's details. */
@Serializable
data class InquiryDraft(
    val title: String = "",
    val explanation: String = "",
    val expectation: String = "",
    val types: Set<ProjectType> = emptySet(),
    val projectFor: ProjectFor = ProjectFor.BauProject,
    val classCode: String? = null,
    val rewards: Set<RewardType> = setOf(RewardType.Price),
    val priceUsd: Int? = null,
    val schoolDiscountPercent: Int? = null,
    val priority: InquiryPriority = InquiryPriority.Normal,
    /** ISO date yyyy-MM-dd. */
    val deadline: String? = null,
    /** Null = "Let AI suggest". */
    val difficulty: Difficulty? = null,
)

/** The AI's rewrite of a draft ("Ready to Post"). */
@Serializable
data class RefinedInquiry(
    val title: String,
    val brief: String,
    val doneLooksLike: List<String>,
    val types: Set<ProjectType>,
    val difficulty: Difficulty,
    val minScore: Int,
    val rewardSummary: String,
    val deadline: String?,
    val whatAiChanged: String,
    /** e.g. "Ilias and Arian, the senior engineers" — who is notified when it's done. */
    val notifiesOnDone: String? = null,
)

@Serializable
data class InquiryBlocker(
    val id: String,
    val text: String,
    val raisedBy: String,
    val raisedAt: String,
    val resolved: Boolean = false,
)

@Serializable
data class InquiryLogEntry(val date: String, val summary: String, val blockers: String = "No blockers")

/** Daily progress of an inquiry that students are building. */
@Serializable
data class InquiryProgress(
    val today: String,
    val dayOf: Int,
    val totalDays: Int,
    val percentDone: Int,
    val doneToday: List<String>,
    val blocker: InquiryBlocker? = null,
    val log: List<InquiryLogEntry> = emptyList(),
    val studentNames: List<String> = emptyList(),
)

@Serializable
data class StaffInquiry(
    val id: String,
    val draft: InquiryDraft,
    val status: InquiryStatus,
    val refined: RefinedInquiry? = null,
    val progress: InquiryProgress? = null,
    /** One-line status shown in lists, e.g. "Day 4 of 7 · 1 blocker", "Ilias & Arian notified", "$20 budget wasted". */
    val latest: String = "",
    val rewardLabel: String = "",
    val reviewedBy: String? = null,
    /** Not published yet: it can be deleted outright instead of cancelled. */
    val canDelete: Boolean = false,
    /** Unspent budget of a cancelled inquiry (USD). */
    val wastedUsd: Int = 0,
    /** ISO instants from the backend. */
    val updatedAt: String? = null,
    val cancelledAt: String? = null,
) {
    val title: String get() = refined?.title ?: draft.title
    /** "BAU Project · Urgent" / "Class Project · CMPS433". */
    val subtitle: String
        get() = buildList {
            add(draft.projectFor.label)
            if (draft.projectFor == ProjectFor.ClassProject && draft.classCode != null) add(draft.classCode)
            if (draft.priority == InquiryPriority.Urgent) add("Urgent")
        }.joinToString(" · ")
}

@Serializable
data class StaffOverview(
    val raisedUsd: Int,
    val wastedUsd: Int,
    val openCount: Int,
    val inProgressCount: Int,
    val deliveredCount: Int,
    val periodLabel: String = "This Semester",
    /** Inquiry with an unresolved blocker ("Needs your attention"), if any. */
    val needsAttention: StaffInquiry? = null,
)

/** Real-world project ideas universities can take on — shown on Overview when the user has no inquiries yet. */
@Serializable
data class WorldProject(
    val id: String,
    val title: String,
    val summary: String,
    val organization: String,
    val types: Set<ProjectType> = emptySet(),
    val url: String? = null,
)

@Serializable
data class StaffProfile(
    val department: String,
    /** "Professor", "Lecturer", "Administrator"… */
    val title: String,
    /** Classes they teach; empty for non-professors ("No classes"). */
    val classes: List<String>,
    val projectsPosted: Int,
    val delivered: Int,
    val raisedUsd: Int,
    /** Senior engineers notified when this user's projects are done (and who run the Lead interviews). */
    val seniorEngineers: List<SeniorEngineer>,
    val notifyWhenDone: Boolean = true,
    val notifyWhenStudentJoins: Boolean = true,
)

/**
 * A BAU BUILD senior engineer (e.g. Ilias, Tech Lead Engineer). Listed on the staff Profile; tapping one opens
 * their bio. List items may carry only some fields — the full profile comes from `StaffRepository.seniorEngineer`.
 */
@Serializable
data class SeniorEngineer(
    val id: String,
    val name: String,
    /** "Tech Lead Engineer", "Senior Engineer"; may be blank when the backend doesn't send one. */
    val title: String,
    val bio: String,
    val avatarUrl: String? = null,
    val skills: List<String> = emptyList(),
    val githubUsername: String? = null,
    val email: String? = null,
    /** Runs the Lead interviews for students. */
    val isInterviewer: Boolean = true,
) {
    /** Same rule as [User.initials]: "I" for "Ilias", "AB" for "Arian Bek". */
    val initials: String
        get() = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}
