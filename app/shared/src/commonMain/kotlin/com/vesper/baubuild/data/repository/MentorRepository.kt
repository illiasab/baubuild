package com.vesper.baubuild.data.repository

import kotlinx.coroutines.flow.Flow

/** Who wrote a Mentor AI chat message. The backend says `user` / `assistant` (older rows: `student` / `mentor`). */
enum class MentorRole { User, Assistant }

/** A knowledge-base source the mentor used: a BAU standard (`rule_id`) or a document. */
data class MentorCitation(
    val title: String,
    val ruleId: String? = null,
    val documentId: String? = null,
) {
    /** Short chip label: the rule id when there is one, else the title. */
    val label: String get() = ruleId?.takeIf { it.isNotBlank() } ?: title
}

/** One stored message of a project's Mentor AI chat. */
data class MentorChatMessage(
    val id: String,
    val role: MentorRole,
    val text: String,
    val taskId: String? = null,
    val citations: List<MentorCitation> = emptyList(),
    val createdAt: String? = null,
)

/** One server-sent event of a streamed mentor reply (`POST /v1/projects/{id}/mentor/messages`). */
sealed interface MentorEvent {
    /** Progress line shown while the reply has no text yet ("Checking BAU standards"). */
    data class Status(val label: String) : MentorEvent
    data class Citation(val citation: MentorCitation) : MentorEvent
    /** Next piece of the reply; append it to the assistant bubble. */
    data class Delta(val text: String) : MentorEvent
    /** The reply is complete. [text] is the full reply when the server sends it. */
    data class Done(val messageId: String? = null, val text: String? = null) : MentorEvent
    /** The server gave up mid-stream. */
    data class Error(val code: String, val message: String, val retryAfterSeconds: Long? = null) : MentorEvent
}

/**
 * Mentor AI chat of one project (contract: server/go `mentor/messages`).
 * HTTP errors (e.g. `429 quota_exceeded` with `retry_after_s`, `403 not_a_member`) are thrown from [send]'s flow as
 * [com.vesper.baubuild.core.network.ApiException].
 */
interface MentorRepository {
    /** The student's chat history for [projectId], oldest first. */
    suspend fun history(projectId: String): List<MentorChatMessage>

    /** Asks the mentor; the cold flow streams the reply. Cancel the collector to abort the request. */
    fun send(projectId: String, text: String, taskId: String? = null): Flow<MentorEvent>

    companion object {
        /** Server limit: "Ask a question of 1 to 4000 characters." */
        const val MAX_MESSAGE_LENGTH = 4000
    }
}
