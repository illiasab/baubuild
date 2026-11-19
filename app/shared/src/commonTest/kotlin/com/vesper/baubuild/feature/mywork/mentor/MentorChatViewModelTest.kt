package com.vesper.baubuild.feature.mywork.mentor

import com.vesper.baubuild.core.network.ApiException
import com.vesper.baubuild.data.repository.MentorChatMessage
import com.vesper.baubuild.data.repository.MentorCitation
import com.vesper.baubuild.data.repository.MentorEvent
import com.vesper.baubuild.data.repository.MentorRepository
import com.vesper.baubuild.data.repository.MentorRole
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted [MentorRepository]: each send gets a channel the test feeds events (or a failure) into. */
private class ScriptedMentorRepository(
    var historyResult: () -> List<MentorChatMessage> = { emptyList() },
) : MentorRepository {
    data class Sent(val projectId: String, val text: String, val taskId: String?, val events: Channel<MentorEvent>)
    val sent = mutableListOf<Sent>()
    var cancelled = 0
    var historyCalls = 0

    override suspend fun history(projectId: String): List<MentorChatMessage> {
        historyCalls++
        return historyResult()
    }

    override fun send(projectId: String, text: String, taskId: String?): Flow<MentorEvent> {
        val channel = Channel<MentorEvent>(Channel.UNLIMITED)
        sent += Sent(projectId, text, taskId, channel)
        return channel.consumeAsFlow().onCompletion { if (it is kotlinx.coroutines.CancellationException) cancelled++ }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MentorChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsHistory() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository({ listOf(MentorChatMessage("a", MentorRole.User, "Hi"), MentorChatMessage("b", MentorRole.Assistant, "Hello")) })
        val vm = MentorChatViewModel(repo, "p")
        assertTrue(vm.state.value.isLoadingHistory)
        advanceUntilIdle()
        assertEquals(listOf("Hi", "Hello"), vm.state.value.messages.map { it.text })
        assertFalse(vm.state.value.isLoadingHistory)
        assertFalse(vm.state.value.isEmpty)
    }

    @Test fun historyFailureKeepsChatUsableAndRetries() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository({ throw ApiException(503, "unavailable", "down") })
        val vm = MentorChatViewModel(repo, "p")
        advanceUntilIdle()
        assertEquals("Couldn’t load earlier messages.", vm.state.value.historyError)
        repo.historyResult = { listOf(MentorChatMessage("a", MentorRole.User, "Hi")) }
        vm.loadHistory()
        advanceUntilIdle()
        assertNull(vm.state.value.historyError)
        assertEquals(1, vm.state.value.messages.size)
    }

    @Test fun streamingAppendsDeltasIntoTheAssistantBubble() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository()
        val vm = MentorChatViewModel(repo, "p", initialTaskId = "t1") { "Fix the form" }
        advanceUntilIdle()
        assertEquals(MentorTaskContext("t1", "Fix the form"), vm.state.value.taskContext)

        vm.onDraftChange("  How do I test this?  ")
        assertTrue(vm.send())
        runCurrent()
        val call = repo.sent.single()
        assertEquals(Triple("p", "How do I test this?", "t1"), Triple(call.projectId, call.text, call.taskId))
        var s = vm.state.value
        assertEquals("", s.draft)
        assertTrue(s.isStreaming)
        assertFalse(s.canSend)
        assertEquals(MentorChatViewModel.THINKING, s.messages.last().status)

        call.events.send(MentorEvent.Status("Checking BAU standards")); runCurrent()
        assertEquals("Checking BAU standards", vm.state.value.messages.last().status)
        call.events.send(MentorEvent.Citation(MentorCitation("Validate input", "BAU-SEC-01"))); runCurrent()
        call.events.send(MentorEvent.Delta("Try ")); runCurrent()
        assertEquals("Try ", vm.state.value.messages.last().text)
        assertTrue(vm.state.value.messages.last().isStreaming)
        call.events.send(MentorEvent.Delta("`assert`.")); runCurrent()
        assertEquals("Try `assert`.", vm.state.value.messages.last().text)

        vm.onDraftChange("next one")
        assertFalse(vm.send(), "send is disabled while streaming")

        call.events.send(MentorEvent.Done("m1", "Try `assert`.")); call.events.close(); advanceUntilIdle()
        s = vm.state.value
        assertFalse(s.isStreaming)
        assertEquals(listOf(MentorRole.User, MentorRole.Assistant), s.messages.map { it.role })
        val reply = s.messages.last()
        assertEquals("Try `assert`.", reply.text)
        assertFalse(reply.isStreaming)
        assertNull(reply.status)
        assertEquals("BAU-SEC-01", reply.citations.single().label)
        assertEquals("next one", s.draft, "typing during the stream is kept")
        assertTrue(s.canSend)
    }

    @Test fun doneTextFillsAnEmptyReply() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository()
        val vm = MentorChatViewModel(repo, "p")
        advanceUntilIdle()
        vm.send("q"); runCurrent()
        repo.sent.single().events.apply { send(MentorEvent.Done("m", "Whole reply")); close() }
        advanceUntilIdle()
        assertEquals("Whole reply", vm.state.value.messages.last().text)
    }

    @Test fun failureKeepsDraftAndRetryResends() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository()
        val vm = MentorChatViewModel(repo, "p")
        advanceUntilIdle()
        vm.onDraftChange("Why does it fail?")
        vm.send(); runCurrent()
        repo.sent[0].events.apply { send(MentorEvent.Delta("Partial")); close(IllegalStateException("socket closed")) }
        advanceUntilIdle()

        var s = vm.state.value
        assertFalse(s.isStreaming)
        assertEquals("Why does it fail?", s.draft, "draft comes back on failure")
        assertTrue(s.messages[0].failed)
        val error = s.messages[1]
        assertTrue(error.isError)
        assertTrue(error.canRetry)
        assertEquals(MentorChatViewModel.UNAVAILABLE, error.text)

        vm.retry(); runCurrent()
        assertEquals("Why does it fail?", repo.sent[1].text)
        s = vm.state.value
        assertEquals("", s.draft, "retrying the same question clears the restored draft")
        assertEquals(2, s.messages.size, "the failed attempt is replaced")
        repo.sent[1].events.apply { send(MentorEvent.Delta("Because…")); close() }
        advanceUntilIdle()
        assertEquals(listOf("Why does it fail?", "Because…"), vm.state.value.messages.map { it.text })
        assertFalse(vm.state.value.messages.any { it.failed || it.isError })
    }

    @Test fun streamErrorEventIsAFailure() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository()
        val vm = MentorChatViewModel(repo, "p")
        advanceUntilIdle()
        vm.send("q"); runCurrent()
        repo.sent[0].events.apply { send(MentorEvent.Error("provider_down", "x")); close() }
        advanceUntilIdle()
        assertTrue(vm.state.value.messages.last().isError)
        assertEquals("q", vm.state.value.draft)
    }

    @Test fun quotaExceededExplainsTheLimitWithoutRetry() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository()
        val vm = MentorChatViewModel(repo, "p")
        advanceUntilIdle()
        vm.send("q"); runCurrent()
        repo.sent[0].events.close(ApiException(429, "quota_exceeded", "The mentor limit for today is 60 messages.", retryAfterSeconds = 3600))
        advanceUntilIdle()
        val error = vm.state.value.messages.last()
        assertTrue(error.isError)
        assertFalse(error.canRetry)
        assertEquals("The mentor limit for today is 60 messages. Try again in about an hour.", error.text)
        assertEquals("q", vm.state.value.draft)
    }

    @Test fun stopCancelsTheStream() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository()
        val vm = MentorChatViewModel(repo, "p")
        advanceUntilIdle()
        vm.send("first"); runCurrent()
        repo.sent[0].events.send(MentorEvent.Delta("Half an")); runCurrent()
        vm.stop(); advanceUntilIdle()
        assertEquals(1, repo.cancelled)
        var s = vm.state.value
        assertFalse(s.isStreaming)
        assertEquals("Half an", s.messages.last().text, "partial text stays")
        assertFalse(s.messages.last().isStreaming)

        // Stopping before any text drops the empty bubble and puts the question back in the draft.
        vm.send("second"); runCurrent()
        vm.stop(); advanceUntilIdle()
        assertEquals(2, repo.cancelled)
        s = vm.state.value
        assertEquals("second", s.draft)
        assertEquals(2, s.messages.size)
    }

    @Test fun leavingTheScreenCancelsTheStream() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository()
        val store = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider.create(
            store,
            viewModelFactory { initializer { MentorChatViewModel(repo, "p") } },
        )[MentorChatViewModel::class]
        advanceUntilIdle()
        vm.send("q"); runCurrent()
        store.clear() // the destination's ViewModelStore is cleared when the screen is popped
        advanceUntilIdle()
        assertEquals(1, repo.cancelled)
    }

    @Test fun draftIsCappedAndCounted() = runTest(dispatcher) {
        val vm = MentorChatViewModel(ScriptedMentorRepository(), "p")
        vm.onDraftChange("a".repeat(3000))
        assertNull(vm.state.value.counter)
        vm.onDraftChange("a".repeat(5000))
        assertEquals(4000, vm.state.value.draft.length)
        assertEquals("4000 / 4000", vm.state.value.counter)
        assertTrue(vm.state.value.canSend)
        vm.onDraftChange("   ")
        assertFalse(vm.state.value.canSend)
        assertFalse(vm.send())
    }

    @Test fun taskContextCanBeSetAndCleared() = runTest(dispatcher) {
        val repo = ScriptedMentorRepository()
        val vm = MentorChatViewModel(repo, "p")
        advanceUntilIdle()
        vm.setTaskContext("t2", "Write tests")
        vm.send("q"); runCurrent()
        assertEquals("t2", repo.sent[0].taskId)
        repo.sent[0].events.apply { send(MentorEvent.Delta("ok")); close() }
        advanceUntilIdle()
        vm.clearTaskContext()
        vm.send("q2"); runCurrent()
        assertNull(repo.sent[1].taskId)
    }
}

class MentorFormattingTest {
    @Test fun backtickSegments() {
        assertEquals(listOf("Try " to false, "a.js" to true, " now" to false), mentorSegments("Try `a.js` now"))
        assertEquals(listOf("plain" to false), mentorSegments("plain"))
        assertEquals(listOf("a" to false, "b" to true, "c`d" to false), mentorSegments("a`b`c`d"))
    }

    @Test fun fencedCodeBlocks() {
        assertEquals(
            listOf(MentorBlock.Prose("Try this:"), MentorBlock.Code("val x = 1\nprintln(x)", "kotlin"), MentorBlock.Prose("Then run it.")),
            mentorBlocks("Try this:\n```kotlin\nval x = 1\nprintln(x)\n```\nThen run it."),
        )
        assertEquals(listOf(MentorBlock.Code("a b")), mentorBlocks("```a b```"))
        // Still streaming: an unclosed fence is code to the end.
        assertEquals(listOf(MentorBlock.Prose("See"), MentorBlock.Code("x = 1", "js")), mentorBlocks("See\n```js\nx = 1"))
    }
}
