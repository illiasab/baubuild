package com.vesper.baubuild.data.http.staff

import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.InquiryDraft
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.RefinedInquiry
import com.vesper.baubuild.core.model.RewardType

/**
 * CLIENT-SIDE FALLBACK for "AI refine". The backend has no refine endpoint yet (wanted:
 * `POST /v1/staff/inquiries/refine` {draft} → RefinedInquiry), so the draft is tidied up locally with the same
 * client-side heuristic. Replace the call site in [HttpStaffRepository.refine] once the endpoint exists.
 */
internal fun heuristicRefine(draft: InquiryDraft): RefinedInquiry {
    val title = draft.title.trim().replaceFirstChar { it.uppercase() }
    val types = draft.types.ifEmpty { setOf(ProjectType.Other) }
    val difficulty = draft.difficulty ?: when {
        types.size >= 3 || (draft.priceUsd ?: 0) >= 50 -> Difficulty.Hard
        types.size == 2 -> Difficulty.Medium
        else -> Difficulty.Easy
    }
    return RefinedInquiry(
        title = if (title.startsWith("Build", ignoreCase = true)) title else "Build $title".trim(),
        brief = draft.explanation.trim().replaceFirstChar { it.uppercase() }.let { if (it.isEmpty() || it.endsWith('.')) it else "$it." } +
            (if (draft.expectation.isNotBlank()) " ${draft.expectation.trim().replaceFirstChar { it.uppercase() }}." else ""),
        doneLooksLike = splitDoneItems(draft.expectation, splitCommas = true)
            .ifEmpty { listOf("The brief is delivered and reviewed by a dev lead") }
            .take(4),
        types = types,
        difficulty = difficulty,
        minScore = defaultMinScore(difficulty),
        rewardSummary = rewardLabel(draft),
        deadline = draft.deadline,
        whatAiChanged = "Rewrote the title and brief in plain language, turned your expectations into acceptance checks, " +
            "and suggested ${difficulty.name} difficulty.",
    )
}

internal fun defaultMinScore(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.Easy -> 0
    Difficulty.Medium -> 150
    Difficulty.Hard -> 300
}

/** "$40 + SSN". */
internal fun rewardLabel(d: InquiryDraft): String = buildList {
    if (RewardType.Price in d.rewards && d.priceUsd != null) add("$${d.priceUsd}")
    if (RewardType.Ssn in d.rewards) add("SSN")
    if (RewardType.JobAtBau in d.rewards) add("Job at BAU")
    if (RewardType.SchoolDiscount in d.rewards) add("${d.schoolDiscountPercent ?: 0}% off")
    if (RewardType.Volunteer in d.rewards) add("Volunteer")
}.joinToString(" + ").ifEmpty { "—" }

/** Expectation text → acceptance items. One item per line; a single line is split on sentences (and commas). */
internal fun splitDoneItems(expectation: String, splitCommas: Boolean = false): List<String> {
    val lines = expectation.lines().map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() }
    val pieces = if (lines.size > 1) lines else {
        val seps = if (splitCommas) charArrayOf('.', ',') else charArrayOf('.')
        expectation.split(*seps, '\n')
    }
    return pieces.map { it.trim().trimEnd('.') }.filter { it.length > 3 }.map { it.replaceFirstChar { c -> c.uppercase() } }
}
