package com.vesper.baubuild.feature.mywork.portal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MentorAuthor { Mentor, Me }

/** One chat bubble. Text may contain `backtick` spans, rendered as inline code. */
data class MentorMessage(val id: Long, val author: MentorAuthor, val text: String)

data class MentorChatUiState(
    val messages: List<MentorMessage> = emptyList(),
    /** True between the user's send and the mentor's reply. Send is disabled meanwhile. */
    val isReplying: Boolean = false,
)

/**
 * In-memory Mentor AI chat for one project workspace (portal design).
 *
 * TODO(mentor-ai): replace with the real contract once the backend has one, e.g.
 *  `MentorRepository.history(projectId): List<MentorMessage>` and
 *  `MentorRepository.send(projectId, text): Flow<MentorReplyChunk>` (streamed), appending chunks to the last
 *  mentor bubble. On send failure keep the user's message and show an inline retry (zero-downtime rule).
 */
class MentorChatViewModel(
    val projectId: String,
    seed: List<MentorMessage> = emptyList(),
    private val replyDelayMillis: Long = 900,
) : ViewModel() {
    private val _state = MutableStateFlow(MentorChatUiState(messages = seed))
    val state: StateFlow<MentorChatUiState> = _state.asStateFlow()

    private var nextId: Long = (seed.maxOfOrNull { it.id } ?: 0L) + 1

    /** Appends the user's message and, after a short delay, a placeholder reply. Returns false if nothing was sent. */
    fun send(text: String): Boolean {
        val message = text.trim()
        if (message.isEmpty() || _state.value.isReplying) return false
        _state.update { it.copy(messages = it.messages + MentorMessage(nextId++, MentorAuthor.Me, message), isReplying = true) }
        viewModelScope.launch {
            delay(replyDelayMillis)
            _state.update {
                it.copy(
                    messages = it.messages + MentorMessage(nextId++, MentorAuthor.Mentor, PLACEHOLDER_REPLY),
                    isReplying = false,
                )
            }
        }
        return true
    }

    companion object {
        // TODO(mentor-ai): remove once replies come from the backend.
        const val PLACEHOLDER_REPLY =
            "I’m not connected yet, so I can’t answer this one for real. Mentor AI is coming soon; until then, your dev lead can help."
    }
}

/** Splits text on backticks: odd segments are inline code. Unmatched trailing backtick is kept as text. */
internal fun mentorSegments(text: String): List<Pair<String, Boolean>> {
    val parts = text.split('`')
    if (parts.size % 2 == 0) {
        // Odd number of backticks: treat the last one literally.
        val head = parts.dropLast(2)
        val tail = parts[parts.size - 2] + "`" + parts.last()
        return (head + tail).mapIndexed { i, s -> s to (i % 2 == 1) }.filter { it.first.isNotEmpty() }
    }
    return parts.mapIndexed { i, s -> s to (i % 2 == 1) }.filter { it.first.isNotEmpty() }
}
