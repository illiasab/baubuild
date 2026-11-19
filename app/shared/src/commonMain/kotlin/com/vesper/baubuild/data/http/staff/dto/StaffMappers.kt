package com.vesper.baubuild.data.http.staff.dto

import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.InquiryDraft
import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.ProjectFor
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.RefinedInquiry
import com.vesper.baubuild.core.model.RewardType
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.core.model.WorldProject
import com.vesper.baubuild.data.http.staff.defaultMinScore
import com.vesper.baubuild.data.http.staff.rewardLabel
import com.vesper.baubuild.data.http.staff.splitDoneItems

/**
 * Backend inquiry status → app [InquiryStatus]. Contract lifecycle: submitted → approved → published → closed.
 *
 * | backend                               | app          | list line ("latest")                 |
 * |---------------------------------------|--------------|--------------------------------------|
 * | submitted                             | Posted       | Waiting for review                   |
 * | approved                              | Posted       | Approved · going live soon           |
 * | published                             | Posted       | Live on the board                    |
 * | in_progress (not sent today)          | InProgress   | Students are building it             |
 * | closed / done / delivered             | Done         | Delivered                            |
 * | cancelled / canceled / rejected / withdrawn | Cancelled | Withdrawn                          |
 * | draft (not sent today)                | Draft        | Draft                                |
 * | anything else                         | Posted       | (raw status)                         |
 *
 * Draft and AiRefining never come from the backend today: drafts live on the device (see HttpStaffRepository)
 * and refinement happens before posting. A deleted inquiry simply disappears from `GET /v1/inquiries/mine`.
 */
internal fun String.toStatus(): InquiryStatus = when (lowercase()) {
    "submitted", "approved", "published", "posted", "open" -> InquiryStatus.Posted
    "in_progress", "inprogress", "building" -> InquiryStatus.InProgress
    "closed", "done", "delivered", "completed" -> InquiryStatus.Done
    "cancelled", "canceled", "rejected", "withdrawn" -> InquiryStatus.Cancelled
    "draft" -> InquiryStatus.Draft
    else -> InquiryStatus.Posted
}

private fun latestFor(status: String): String = when (status.lowercase()) {
    "submitted" -> "Waiting for review"
    "approved" -> "Approved · going live soon"
    "published", "posted", "open" -> "Live on the board"
    else -> when (status.toStatus()) {
        InquiryStatus.InProgress -> "Students are building it"
        InquiryStatus.Done -> "Delivered"
        InquiryStatus.Cancelled -> "Withdrawn"
        InquiryStatus.Draft -> "Draft"
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

internal fun String.toDifficulty(): Difficulty? = when (lowercase()) {
    "easy" -> Difficulty.Easy
    "medium" -> Difficulty.Medium
    "hard" -> Difficulty.Hard
    else -> null
}

internal fun Difficulty.wire(): String = name.lowercase()

/** Lenient project-type parsing: enum name ("IosApp"), label ("iOS App") or snake case ("ios_app"). */
internal fun projectTypeOf(raw: String): ProjectType? {
    val key = raw.trim().lowercase().filter { it.isLetterOrDigit() }
    return ProjectType.entries.firstOrNull { t ->
        t.name.lowercase() == key || t.label.lowercase().filter { it.isLetterOrDigit() } == key
    }
}

/** "yyyy-MM-dd" (app) → ISO instant at the end of that day UTC (backend `due_at`). */
internal fun deadlineToDueAt(deadline: String?): String? =
    deadline?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length == 10) "${it}T23:59:59Z" else it }

/** Backend `due_at` (ISO instant) → "yyyy-MM-dd". */
internal fun dueAtToDeadline(dueAt: String?): String? = dueAt?.trim()?.takeIf { it.length >= 10 }?.take(10)

/**
 * The backend inquiry has one free-text `description`. App-only fields (acceptance checks, project types,
 * class project + code, non-cash rewards) have no columns yet, so they are written into the description in a
 * human-readable footer that students also see, and parsed back on read:
 *
 * ```
 * <brief>
 *
 * What done looks like:
 * - Students see live availability
 * - Two bookings can never overlap
 *
 * Project type: iOS App, Android App
 * For: Class Project · CMPS433
 * Reward: $40 + SSN
 * ```
 * Any description without the footer parses as a plain brief.
 */
internal object InquiryDescription {
    const val DONE_HEADER = "What done looks like:"
    private const val TYPES = "Project type: "
    private const val FOR = "For: "
    private const val REWARD = "Reward: "

    data class Parsed(
        val body: String,
        val done: List<String>,
        val types: Set<ProjectType>,
        val projectFor: ProjectFor?,
        val classCode: String?,
        val rewards: Set<RewardType>?,
        val schoolDiscountPercent: Int?,
    )

    fun compose(
        body: String,
        done: List<String>,
        types: Set<ProjectType>,
        projectFor: ProjectFor,
        classCode: String?,
        draft: InquiryDraft,
    ): String = buildString {
        append(body.trim())
        if (done.isNotEmpty()) {
            append("\n\n").append(DONE_HEADER)
            done.forEach { append("\n- ").append(it.trim()) }
        }
        val footer = buildList {
            if (types.isNotEmpty()) add(TYPES + ProjectType.entries.filter { it in types }.joinToString(", ") { it.label })
            add(FOR + projectFor.label + (if (projectFor == ProjectFor.ClassProject && !classCode.isNullOrBlank()) " · ${classCode.trim()}" else ""))
            val reward = rewardLabel(draft)
            if (reward != "—") add(REWARD + reward)
        }
        append("\n\n").append(footer.joinToString("\n"))
    }.trim()

    fun parse(description: String): Parsed {
        val body = mutableListOf<String>()
        val done = mutableListOf<String>()
        var types = emptySet<ProjectType>()
        var projectFor: ProjectFor? = null
        var classCode: String? = null
        var rewards: Set<RewardType>? = null
        var discount: Int? = null
        var inDone = false
        for (raw in description.lines()) {
            val line = raw.trim()
            when {
                line == DONE_HEADER -> inDone = true
                line.startsWith(TYPES) && line.removePrefix(TYPES).split(',').any { projectTypeOf(it) != null } ->
                    types = line.removePrefix(TYPES).split(',').mapNotNull { projectTypeOf(it) }.toSet()
                line.startsWith(FOR) && ProjectFor.entries.any { line.removePrefix(FOR).startsWith(it.label, ignoreCase = true) } -> {
                    val parts = line.removePrefix(FOR).split('·').map { it.trim() }
                    projectFor = ProjectFor.entries.firstOrNull { it.label.equals(parts.first(), ignoreCase = true) }
                    classCode = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                }
                line.startsWith(REWARD) -> {
                    val set = mutableSetOf<RewardType>()
                    line.removePrefix(REWARD).split('+').map { it.trim() }.forEach { token ->
                        when {
                            token.startsWith("$") -> set += RewardType.Price
                            token.equals("SSN", true) -> set += RewardType.Ssn
                            token.equals("Job at BAU", true) -> set += RewardType.JobAtBau
                            token.endsWith("% off", true) -> {
                                set += RewardType.SchoolDiscount
                                discount = token.substringBefore('%').trim().toIntOrNull()
                            }
                            token.equals("Volunteer", true) -> set += RewardType.Volunteer
                        }
                    }
                    rewards = set
                }
                inDone && line.startsWith("- ") -> done += line.removePrefix("- ").trim()
                inDone && line.isEmpty() -> Unit
                else -> { inDone = false; body += raw }
            }
        }
        return Parsed(body.joinToString("\n").trim(), done, types, projectFor, classCode, rewards, discount)
    }
}

/** Backend inquiry → app [StaffInquiry]. The posted text is both the draft (editable) and the refined version. */
internal fun InquiryDto.toStaffInquiry(): StaffInquiry {
    val parsed = InquiryDescription.parse(description)
    val difficulty = difficulty.toDifficulty()
    val projectFor = parsed.projectFor ?: if (reviewerType == "professor") ProjectFor.ClassProject else ProjectFor.BauProject
    val rewards = (parsed.rewards ?: setOf(RewardType.Price)).let { if (bounty > 0) it + RewardType.Price else it }
    val draft = InquiryDraft(
        title = title,
        explanation = parsed.body,
        expectation = parsed.done.joinToString("\n"),
        types = parsed.types,
        projectFor = projectFor,
        classCode = parsed.classCode,
        rewards = rewards,
        priceUsd = bounty.takeIf { it > 0 || RewardType.Price in rewards },
        schoolDiscountPercent = parsed.schoolDiscountPercent,
        priority = if (isUrgent) InquiryPriority.Urgent else InquiryPriority.Normal,
        deadline = dueAtToDeadline(dueAt),
        difficulty = difficulty,
    )
    val reward = rewardLabel(draft)
    val effectiveDifficulty = difficulty ?: Difficulty.Medium
    return StaffInquiry(
        id = id,
        draft = draft,
        status = status.toStatus(),
        refined = RefinedInquiry(
            title = title,
            brief = parsed.body,
            doneLooksLike = parsed.done,
            types = parsed.types,
            difficulty = effectiveDifficulty,
            minScore = minScore.takeIf { it >= 0 } ?: defaultMinScore(effectiveDifficulty),
            rewardSummary = reward,
            deadline = draft.deadline,
            whatAiChanged = "",
        ),
        latest = latestFor(status),
        rewardLabel = reward,
        reviewedBy = when (reviewerType) {
            "professor" -> "professor"
            "ai_then_lead" -> "dev lead"
            else -> null
        },
    )
}

internal fun WorldProjectDto.toWorldProject(): WorldProject = WorldProject(
    id = id,
    title = title,
    summary = summary ?: description ?: "",
    organization = organization,
    types = types.mapNotNull { projectTypeOf(it) }.toSet(),
    url = url,
)
