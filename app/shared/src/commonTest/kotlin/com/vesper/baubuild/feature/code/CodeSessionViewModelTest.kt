package com.vesper.baubuild.feature.code

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.data.fake.FakeData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CodeSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val project = FakeData.roomBooking.id
    private val form = "src/booking-form.js"

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(github: TestGitHub = TestGitHub(), store: CodeSessionStore = CodeSessionStore(), task: String = "t1") =
        CodeSessionViewModel(TestWork(), github, project, task, store).also { advanceUntilIdle() }

    @Test fun createsTaskBranchAndLoadsTree() = runTest(dispatcher) {
        val gh = TestGitHub()
        val vm = vm(gh)
        val ctx = assertIs<LoadState.Content<CodeContext>>(vm.state.value.setup).data
        assertEquals("task/t1-reject-past-dates-in-the-booking-form", ctx.branch.name)
        assertEquals("bau-build/library-booking", ctx.repoFullName)
        assertEquals(listOf(ctx.branch.name), gh.branchCalls)
        assertTrue(vm.state.value.visibleTree.any { it.path == "src" })
        assertTrue("src" in vm.state.value.expandedDirs)
        assertEquals("Reject past dates in the booking form", vm.state.value.commitMessage)
    }

    @Test fun waitsForGitHubConnectionThenStarts() = runTest(dispatcher) {
        val gh = TestGitHub(connected = false)
        val vm = vm(gh)
        assertTrue(vm.state.value.needsGitHub)
        assertIs<LoadState.Loading>(vm.state.value.setup)
        assertTrue(gh.branchCalls.isEmpty())
        gh.connect("token")
        advanceUntilIdle()
        assertFalse(vm.state.value.needsGitHub)
        assertIs<LoadState.Content<*>>(vm.state.value.setup)
    }

    @Test fun branchFailureShowsGitHubMessage() = runTest(dispatcher) {
        val gh = TestGitHub().apply { failBranch = true }
        val vm = vm(gh)
        val failed = assertIs<LoadState.Failed>(vm.state.value.setup)
        assertTrue(failed.message.contains("can't access", ignoreCase = true))
        gh.failBranch = false
        vm.start()
        advanceUntilIdle()
        assertIs<LoadState.Content<*>>(vm.state.value.setup)
    }

    @Test fun openEditMarksDirtyAndSaved() = runTest(dispatcher) {
        val vm = vm()
        vm.openFile(form)
        advanceUntilIdle()
        val draft = assertNotNull(vm.state.value.activeFile)
        assertEquals(form, draft.path)
        assertFalse(draft.isChanged)

        vm.edit(form, draft.text + "// todo\n")
        assertTrue(vm.state.value.activeFile!!.isChanged)
        assertTrue(vm.state.value.activeFile!!.isUnsaved)
        assertEquals(1, vm.state.value.changedCount)

        vm.markSaved()
        assertFalse(vm.state.value.activeFile!!.isUnsaved)
        assertTrue(vm.state.value.activeFile!!.isChanged)

        vm.edit(form, draft.text) // typed back to original
        assertEquals(0, vm.state.value.changedCount)
    }

    @Test fun openingADirectoryTogglesIt() = runTest(dispatcher) {
        val vm = vm()
        assertTrue("src" in vm.state.value.expandedDirs)
        vm.openFile("src")
        assertFalse("src" in vm.state.value.expandedDirs)
        assertTrue(vm.state.value.session.tabs.isEmpty())
    }

    @Test fun tabsAreCappedAndClosingKeepsChangedDrafts() = runTest(dispatcher) {
        val gh = TestGitHub().apply { (1..9).forEach { files["f$it.txt"] = "x$it" } }
        val vm = vm(gh)
        vm.openFile("f1.txt"); advanceUntilIdle()
        vm.edit("f1.txt", "changed")
        (2..9).forEach { vm.openFile("f$it.txt"); advanceUntilIdle() }
        val tabs = vm.state.value.session.tabs
        assertEquals(CodeSessionViewModel.MaxTabs, tabs.size)
        assertTrue("f1.txt" in tabs, "changed tab is kept; the oldest unchanged one makes room")
        assertFalse("f2.txt" in tabs)

        vm.closeTab("f1.txt")
        assertFalse("f1.txt" in vm.state.value.session.tabs)
        assertEquals("changed", vm.state.value.session.files["f1.txt"]?.text)
        assertEquals(listOf("f1.txt"), vm.state.value.changedFiles.map { it.path })
    }

    @Test fun editsSurviveRecreatingTheViewModel() = runTest(dispatcher) {
        val store = CodeSessionStore()
        val gh = TestGitHub()
        val first = vm(gh, store)
        first.openFile(form); advanceUntilIdle()
        first.edit(form, "edited")

        val second = vm(gh, store)
        assertEquals(form, second.state.value.activePath)
        assertEquals("edited", second.state.value.activeFile?.text)
        assertEquals(1, gh.branchCalls.size, "branch comes from the session, no second create")
    }

    @Test fun discardNeedsConfirmation() = runTest(dispatcher) {
        val vm = vm()
        vm.openFile(form); advanceUntilIdle()
        val original = vm.state.value.activeFile!!.text
        vm.edit(form, "oops")
        vm.requestDiscard(form)
        assertEquals(form, vm.state.value.pendingDiscard)
        vm.cancelDiscard()
        assertEquals("oops", vm.state.value.activeFile?.text)

        vm.requestDiscard(form)
        vm.confirmDiscard()
        val d = vm.state.value.activeFile!!
        assertEquals(original, d.text)
        assertEquals(1, d.revision)
        assertNull(vm.state.value.pendingDiscard)
    }

    @Test fun commitSendsIncludedFilesAndClearsDirty() = runTest(dispatcher) {
        val gh = TestGitHub()
        val vm = vm(gh)
        vm.openFile(form); advanceUntilIdle()
        vm.openFile("README.md"); advanceUntilIdle()
        vm.edit(form, "new form")
        vm.edit("README.md", "new readme")
        vm.openCommitSheet()
        vm.toggleCommitFile("README.md") // exclude
        assertEquals(listOf(form), vm.state.value.filesToCommit.map { it.path })
        vm.setCommitMessage("Validate dates")

        vm.commit()
        assertTrue(vm.state.value.isCommitting)
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.isCommitting)
        assertFalse(s.commitSheetVisible)
        assertNull(s.commitError)
        assertEquals("Validate dates", gh.commits.single().second)
        assertEquals("task/t1-reject-past-dates-in-the-booking-form", gh.commits.single().first)
        assertEquals(listOf(form to "new form"), gh.commits.single().third.map { it.path to it.content })
        assertEquals(listOf("README.md"), s.changedFiles.map { it.path }, "excluded file stays changed")
        assertFalse(s.session.files[form]!!.isChanged)
        assertEquals("abc1234", s.lastCommit?.sha?.take(7))
        assertEquals(1, s.session.commits.size)
        assertEquals("Reject past dates in the booking form", s.commitMessage, "message resets to the task title")
    }

    @Test fun failedCommitKeepsEditsAndShowsError() = runTest(dispatcher) {
        val gh = TestGitHub().apply { failCommit = true }
        val vm = vm(gh)
        vm.openFile(form); advanceUntilIdle()
        vm.edit(form, "precious")
        vm.openCommitSheet()
        vm.commit()
        advanceUntilIdle()
        val s = vm.state.value
        assertNotNull(s.commitError)
        assertTrue(s.commitSheetVisible)
        assertEquals("precious", s.activeFile?.text)
        assertTrue(s.activeFile!!.isChanged)
        assertTrue(s.session.commits.isEmpty())

        gh.failCommit = false
        vm.commit()
        advanceUntilIdle()
        assertNull(vm.state.value.commitError)
        assertEquals(0, vm.state.value.changedCount)
    }

    @Test fun commitRequiresChangesAndMessage() = runTest(dispatcher) {
        val gh = TestGitHub()
        val vm = vm(gh)
        assertFalse(vm.state.value.canCommit)
        vm.commit()
        advanceUntilIdle()
        assertEquals("No changes to commit.", vm.state.value.commitError)
        vm.openFile(form); advanceUntilIdle()
        vm.edit(form, "x")
        vm.setCommitMessage("   ")
        assertFalse(vm.state.value.canCommit)
        vm.commit()
        assertEquals("Write a commit message.", vm.state.value.commitError)
        assertTrue(gh.commits.isEmpty())
    }

    @Test fun pullRequestIsGatedOnCommits() = runTest(dispatcher) {
        val gh = TestGitHub()
        val store = CodeSessionStore()
        val vm = vm(gh, store)
        assertFalse(vm.state.value.canOpenPullRequest)
        vm.openPullRequest()
        advanceUntilIdle()
        assertTrue(gh.pullRequests.isEmpty())

        vm.openFile(form); advanceUntilIdle()
        vm.edit(form, "fixed")
        vm.commit(); advanceUntilIdle()
        assertTrue(vm.state.value.canOpenPullRequest)
        vm.openPullRequest(); advanceUntilIdle()
        val pr = assertNotNull(vm.state.value.session.pullRequest)
        assertEquals(42, pr.number)
        val (head, base, body) = gh.pullRequests.single()
        assertEquals("task/t1-reject-past-dates-in-the-booking-form", head)
        assertEquals("main", base)
        assertTrue(body.contains("- [ ] Today’s date is still accepted"))
        assertEquals(pr, store.get(project, "t1").pullRequest)
        assertFalse(vm.state.value.canOpenPullRequest)
    }
}
