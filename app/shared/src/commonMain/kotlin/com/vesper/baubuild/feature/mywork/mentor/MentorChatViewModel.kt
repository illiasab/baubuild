package com.vesper.baubuild.feature.mywork.mentor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.data.repository.MentorChatMessage
import com.vesper.baubuild.data.repository.MentorCitation
import com.vesper.baubuild.data.repository.MentorEvent
import com.vesper.baubuild.data.repository.MentorRepository
import com.vesper.baubuild.data.repository.MentorRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One chat bubble. */
data class MentorBubble(
    val id: String,
    val role: MentorRole,
    val text: String,
    val citations: List<MentorCitation> = emptyList(),
    /** Progress line ("Checking BAU standards") while the reply has no text yet. */
    val status: String? = null,
    /** Deltas are still arriving into this assistant bubble. */
    val isStreaming: Boolean = false,
    /** This assistant bubble is an inline error ([text] says what happened). */
    val isError: Boolean = false,
    /** The error bubble offers "Try again" (not for quota limits). */
    val canRetry: Boolean = false,
    /** User bubble whose question didn't get an answer. */
    val failed: Boolean = false,
)

/** The task the student is asking about ("Ask Mentor AI" on a task). Sent as `task_id`. */
data class MentorTaskContext(val id: String, val title: String? = null)

data class MentorChatUiState(
    val messages: List<MentorBubble> = emptyList(),
    val isLoadingHistory: Boolean = true,
    /** Earlier messages couldn't be loaded; the chat still works (zero downtime). */
    val historyError: String? = null,
    val draft: String = "",
    /** A reply is streaming: Send is disabled. */
    val isStreaming: Boolean = false,
    val taskContext: MentorTaskContext? = null,
) {
    val maxLength: Int get() = MentorRepository.MAX_MESSAGE_LENGTH
    val canSend: Boolean get() = !isStreaming && draft.isNotBlank() && draft.trim().length <= maxLength
    /** "3700 / 4000" once the draft gets close to the limit, else null. */
    val counter: String? get() = if (draft.length > maxLength - 400) "${draft.length} / $maxLength" else null
    val isEmpty: Boolean get() = messages.isEmpty() && !isLoadingHistory
}

/**
 * Mentor AI chat of one project, shared by the mobile Mentor screen and the desktop portal's Mentor panel.
 *
 * Loads the history, streams replies (deltas append live into the assistant bubble, `status` shows a progress line,
 * `citation`s become chips), and on failure keeps the user's question: the draft comes back and the error bubble
 * offers "Try again" (quota errors explain the limit instead). Leaving the screen clears the ViewModel, which
 * cancels the stream with [viewModelScope].
 */
class MentorChatViewModel(
    private val repository: MentorRepository,
    val projectId: String,
    initialTaskId: String? = null,
    /** Looks up a task title for the context chip (mobile only knows the id). */
    private val taskTitle: suspend (taskId: String) -> String? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(
        MentorChatUiState(taskContext = initialTaskId?.takeIf { it.isNotBlank() }?.let { MentorTaskContext(it) }),
    )
    val state: StateFlow<MentorChatUiState> = _state.asStateFlow()

    private var nextLocalId = 0L
    private var streamJob: Job? = null
    private var historyJob: Job? = null

    init {
        loadHistory()
        initialTaskId?.takeIf { it.isNotBlank() }?.let(::resolveTaskTitle)
    }

    fun loadHistory() {
        if (historyJob?.isActive == true) return
        _state.update { it.copy(isLoadingHistory = true, historyError = null) }
        historyJob = viewModelScope.launch {
            try {
                val history = repository.history(projectId).map { it.toBubble() }
                _state.update { s ->
                    // Answered local messages are part of the history now; keep only the pending or failed ones.
                    val pending = if (s.isStreaming) s.messages.takeLast(2).map { it.id }.toSet() else emptySet()
                    val local = s.messages.filter { it.id.startsWith(LOCAL) && (it.id in pending || it.failed || it.isError) }
                    s.copy(messages = history + local, isLoadingHistory = false, historyError = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is ApiException && (e.code == "not_a_member" || e.status == 403)) {
                    "Join the project to talk to the mentor."
                } else {
                    "Couldn’t load earlier messages."
                }
                _state.update { it.copy(isLoadingHistory = false, historyError = message) }
            }
        }
    }

    fun onDraftChange(text: String) {
        _state.update { it.copy(draft = text.take(MentorRepository.MAX_MESSAGE_LENGTH)) }
    }

    /** "Ask Mentor AI" on a task: later questions carry its `task_id`. */
    fun setTaskContext(taskId: String, title: String? = null) {
        _state.update { it.copy(taskContext = MentorTaskContext(taskId, title)) }
        if (title == null) resolveTaskTitle(taskId)
    }

    fun clearTaskContext() {
        _state.update { it.copy(taskContext = null) }
    }

    /** Sends the draft (or a suggestion's [text]). Returns false if nothing was sent. */
    fun send(text: String = _state.value.draft): Boolean {
        val question = text.trim()
        val s = _state.value
        if (question.isEmpty() || s.isStreaming || question.length > MentorRepository.MAX_MESSAGE_LENGTH) return false
        val mine = MentorBubble(localId(), MentorRole.User, question)
        val reply = MentorBubble(localId(), MentorRole.Assistant, "", status = THINKING, isStreaming = true)
        _state.update {
            it.copy(
                // A new question replaces an earlier failed attempt.
                messages = it.messages.withoutFailures() + mine + reply,
                draft = if (it.draft.trim() == question) "" else it.draft,
                isStreaming = true,
            )
        }
        val taskId = s.taskContext?.id
        streamJob = viewModelScope.launch { stream(question, taskId, mine.id, reply.id) }
        return true
    }

    /** Re-sends the question of the failed attempt. */
    fun retry() {
        val messages = _state.value.messages
        val failed = messages.lastOrNull { it.role == MentorRole.User && it.failed } ?: return
        send(failed.text)
    }

    /** Stops the streaming reply. Text received so far stays; an empty reply is dropped and the question goes back to the draft. */
    fun stop() {
        val job = streamJob ?: return
        job.cancel()
        streamJob = null
        _state.update { s ->
            val reply = s.messages.lastOrNull { it.role == MentorRole.Assistant && it.isStreaming }
                ?: return@update s.copy(isStreaming = false)
            if (reply.text.isNotEmpty()) {
                return@update s.copy(
                    isStreaming = false,
                    messages = s.messages.map { if (it.id == reply.id) it.copy(isStreaming = false, status = null) else it },
                )
            }
            val question = s.messages.getOrNull(s.messages.indexOf(reply) - 1)?.takeIf { it.role == MentorRole.User }
            s.copy(
                isStreaming = false,
                messages = s.messages.filter { it.id != reply.id && it.id != question?.id },
                draft = if (question != null) s.draft.ifBlank { question.text } else s.draft,
            )
        }
    }

    private suspend fun stream(question: String, taskId: String?, mineId: String, replyId: String) {
        var failure: Failure? = null
        var gotText = false
        try {
            repository.send(projectId, question, taskId).collect { event ->
                when (event) {
                    is MentorEvent.Status -> updateReply(replyId) { it.copy(status = event.label) }
                    is MentorEvent.Citation -> updateReply(replyId) {
                        if (event.citation in it.citations) it else it.copy(citations = it.citations + event.citation)
                    }
                    is MentorEvent.Delta -> {
                        gotText = true
                        updateReply(replyId) { it.copy(text = it.text + event.text) }
                    }
                    is MentorEvent.Done -> updateReply(replyId) {
                        val full = event.text
                        if (it.text.isEmpty() && !full.isNullOrEmpty()) gotText = true
                        it.copy(text = if (it.text.isEmpty() && !full.isNullOrEmpty()) full else it.text, status = null, isStreaming = false)
                    }
                    is MentorEvent.Error -> failure = failureOf(event.code, event.message, event.retryAfterSeconds, status = null)
                }
            }
            if (failure == null && !gotText) failure = Failure(UNAVAILABLE, retry = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            failure = failureOf(e.code, e.message, e.retryAfterSeconds, e.status)
        } catch (e: Exception) {
            failure = Failure(UNAVAILABLE, retry = true)
        }
        val f = failure
        _state.update { s ->
            if (f == null) {
                s.copy(
                    isStreaming = false,
                    messages = s.messages.map { if (it.id == replyId) it.copy(isStreaming = false, status = null) else it },
                )
            } else {
                s.copy(
                    isStreaming = false,
                    // Keep the user's input on failure (zero downtime) unless they already typed something new.
                    draft = s.draft.ifBlank { question },
                    messages = s.messages.map {
                        when (it.id) {
                            mineId -> it.copy(failed = true)
                            replyId -> it.copy(
                                text = f.message, status = null, isStreaming = false, isError = true,
                                canRetry = f.retry, citations = emptyList(),
                            )
                            else -> it
                        }
                    },
                )
            }
        }
        streamJob = null
    }

    private fun updateReply(id: String, change: (MentorBubble) -> MentorBubble) {
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) change(it) else it }) }
    }

    private fun resolveTaskTitle(taskId: String) {
        viewModelScope.launch {
            val title = runCatching { taskTitle(taskId) }.getOrNull() ?: return@launch
            _state.update { s ->
                if (s.taskContext?.id == taskId && s.taskContext.title == null) s.copy(taskContext = s.taskContext.copy(title = title)) else s
            }
        }
    }

    private fun localId() = "$LOCAL${nextLocalId++}"

    private data class Failure(val message: String, val retry: Boolean)

    private fun failureOf(code: String, message: String, retryAfterSeconds: Long?, status: Int?): Failure =
        if (code == "quota_exceeded" || status == 429) {
            val base = message.takeIf { it.isNotBlank() && !it.startsWith("Request failed") } ?: "You’ve reached today’s mentor limit."
            Failure(listOfNotNull(base, retryAfterSeconds?.let(::retryHint)).joinToString(" "), retry = false)
        } else {
            Failure(UNAVAILABLE, retry = true)
        }

    companion object {
        const val THINKING = "Thinking…"
        const val UNAVAILABLE = "Mentor AI isn’t available right now."
        private const val LOCAL = "local-"

        internal fun retryHint(seconds: Long): String = when {
            seconds <= 60 -> "Try again in a minute."
            seconds < 3600 -> "Try again in ${(seconds + 59) / 60} minutes."
            seconds < 7200 -> "Try again in about an hour."
            else -> "Try again in about ${seconds / 3600} hours."
        }
    }
}

private fun List<MentorBubble>.withoutFailures() = filterNot { it.isError || it.failed }

private fun MentorChatMessage.toBubble() = MentorBubble(id = id, role = role, text = text, citations = citations)
