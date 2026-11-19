package com.vesper.baubuild.feature.projects

import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.MembershipStatus
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.Reviewer
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.model.canCreateInquiry
import com.vesper.baubuild.core.session.SessionState

/** The bits of the signed-in user the Projects tab needs. */
data class Viewer(
    val firstName: String? = null,
    val score: Int = 0,
    val canRequestProject: Boolean = false,
) {
    companion object {
        fun of(session: SessionState): Viewer = when (session) {
            is SessionState.SignedIn -> of(session.user)
            else -> Viewer()
        }

        fun of(user: User) = Viewer(
            firstName = user.firstName.ifBlank { null },
            score = user.score,
            canRequestProject = user.kind.canCreateInquiry(),
        )
    }
}

/** Whether the viewer may tap "I'm In" / "Request" on a project, and why not. */
sealed interface JoinEligibility {
    data object Eligible : JoinEligibility
    data class BelowMinScore(val required: Int, val current: Int) : JoinEligibility
}

fun Project.eligibilityFor(score: Int): JoinEligibility =
    if (minScore > 0 && score < minScore) JoinEligibility.BelowMinScore(minScore, score) else JoinEligibility.Eligible

val Difficulty.label: String get() = name

/** Chip text on rows: "Any score" / "Score 300+". */
val Project.scoreChipLabel: String get() = if (minScore <= 0) "Any score" else "Score $minScore+"

/** Detail value: "Any (0 and up)" / "300 and up". */
val Project.minScoreDetail: String get() = if (minScore <= 0) "Any (0 and up)" else "$minScore and up"

val Project.joinCtaLabel: String get() = if (joinPolicy == JoinPolicy.Open) "I’m In" else "Request"

val Project.joiningDetail: String
    get() = when (joinPolicy) {
        JoinPolicy.Open -> "Everyone welcome"
        JoinPolicy.ApprovalRequired -> when (reviewer) {
            Reviewer.Professor -> "Professor approves"
            else -> "Lead approves"
        }
    }

val Reviewer.label: String
    get() = when (this) {
        Reviewer.AiThenDevLead -> "AI, then a dev lead"
        Reviewer.Professor -> "The professor"
        Reviewer.DevLead -> "A dev lead"
    }

val Project.dueLabel: String get() = if (dueInDays == 1) "1 day" else "$dueInDays days"

/** Footnote below the detail CTA. */
fun Project.ctaFootnote(eligibility: JoinEligibility): String = when (eligibility) {
    is JoinEligibility.BelowMinScore ->
        "This project needs a score of ${eligibility.required}. You have ${eligibility.current}. " +
            "Ship approved projects to raise your score."
    JoinEligibility.Eligible -> when (joinPolicy) {
        JoinPolicy.Open -> "Everyone is welcome. Some projects need a lead’s approval."
        JoinPolicy.ApprovalRequired -> "Your request goes to ${reviewer.label.lowercase()}. You’ll hear back in My Work."
    }
}

fun MembershipStatus.joinedHeadline(): String = when (this) {
    MembershipStatus.In -> "You’re in"
    MembershipStatus.WaitingForApproval -> "Request sent"
    MembershipStatus.InReview -> "In review"
    MembershipStatus.Approved -> "Approved"
}

fun MembershipStatus.joinedDetail(): String = when (this) {
    MembershipStatus.In -> "Find it in My Work to start on your first task."
    MembershipStatus.WaitingForApproval -> "Waiting for approval. You’ll see it in My Work once you’re accepted."
    MembershipStatus.InReview -> "Your work on this project is being reviewed."
    MembershipStatus.Approved -> "This project is approved and on your record."
}

/** "Urgent · Hard · any score welcome" */
val Inquiry.urgentEyebrow: String
    get() = buildList {
        if (isUrgent) add("Urgent")
        add(difficulty.label)
        add(if (minScore <= 0) "any score welcome" else "score $minScore+")
    }.joinToString(" · ")

/** "$35 shuttle timetable widget" */
val Inquiry.urgentHeadline: String
    get() {
        // Lower-case "Shuttle" but keep words like "Wi-Fi" or "CSV" as written.
        val firstWord = title.substringBefore(' ')
        val t = if (firstWord.drop(1).none { it.isUpperCase() }) title.replaceFirstChar { it.lowercaseChar() } else title
        return if (bountyUsd > 0) "$$bountyUsd $t" else title
    }
