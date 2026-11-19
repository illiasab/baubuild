package com.vesper.baubuild.data.http.student.dto

import com.vesper.baubuild.core.model.ChangeStatus
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.Reviewer
import com.vesper.baubuild.core.model.TaskStatus
import kotlin.time.Instant

/**
 * Wire value ↔ domain enum mapping for the student endpoints. Enums are decoded as plain strings in the DTOs and
 * mapped here, so a value a newer backend adds falls back to a safe default instead of failing the whole response.
 */
internal object Wire {
    fun difficulty(value: String?): Difficulty = when (value?.lowercase()) {
        "medium" -> Difficulty.Medium
        "hard" -> Difficulty.Hard
        else -> Difficulty.Easy
    }

    fun difficulty(value: Difficulty): String = value.name.lowercase()

    /** `joining`: `everyone` → open; `approval` (or anything unknown) → approval required (the safer reading). */
    fun joinPolicy(value: String?): JoinPolicy = if (value == "everyone") JoinPolicy.Open else JoinPolicy.ApprovalRequired

    /** `reviewed_by` / `reviewer_type`: `ai_then_lead` | `professor`. */
    fun reviewer(value: String?): Reviewer = when (value) {
        "professor" -> Reviewer.Professor
        "lead", "dev_lead" -> Reviewer.DevLead
        else -> Reviewer.AiThenDevLead
    }

    /** Task `status` + `lane`: a `todo` task in the `backlog` lane is Backlog, otherwise To Do. */
    fun taskStatus(status: String?, lane: String?): TaskStatus = when (status) {
        "in_progress" -> TaskStatus.InProgress
        "done" -> TaskStatus.Done
        else -> if (lane == "backlog") TaskStatus.Backlog else TaskStatus.ToDo
    }

    /** `PATCH /v1/tasks/{id}` accepts `todo | in_progress | done`; the lane can't be changed, so Backlog → todo. */
    fun taskStatus(value: TaskStatus): String = when (value) {
        TaskStatus.Backlog, TaskStatus.ToDo -> "todo"
        TaskStatus.InProgress -> "in_progress"
        TaskStatus.Done -> "done"
    }

    /** Submission `lead_status` / branch commit `state`: `pending | approved | sent_back`. */
    fun changeStatus(value: String?): ChangeStatus = when (value) {
        "approved" -> ChangeStatus.Approved
        "sent_back" -> ChangeStatus.ChangesRequested
        "pending" -> ChangeStatus.WithDevLead
        else -> ChangeStatus.Pushed
    }

    /** "Score 300+" → 300; "Any score" / unknown → 0. Used where the backend only sends `min_score_label`. */
    fun minScoreFromLabel(label: String?): Int =
        label?.let { Regex("""(\d+)""").find(it)?.value?.toIntOrNull() } ?: 0

    fun instant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** Whole days from [now] until [iso] (rounded up, never negative). Unknown/unparseable → 0. */
    fun dueInDays(iso: String?, now: Instant): Int {
        val due = instant(iso) ?: return 0
        val millis = (due - now).inWholeMilliseconds
        if (millis <= 0) return 0
        return ((millis + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** `2026-11-21T10:00:00Z` → `2026-11-21` (the UTC date part). */
    fun isoDate(iso: String?): String? = iso?.takeIf { it.length >= 10 && it[4] == '-' && it[7] == '-' }?.substring(0, 10)

    /** First sentence of [text] for the list-card summary (the backend only has a description). */
    fun summaryOf(text: String): String {
        val trimmed = text.trim()
        val end = Regex("""[.!?](\s|$)""").find(trimmed)?.range?.first ?: return trimmed
        return trimmed.substring(0, end + 1)
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
