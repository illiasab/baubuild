package com.vesper.baubuild.feature.github

import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.github.*
import com.vesper.baubuild.feature.profile.ProfileViewModel
import com.vesper.baubuild.feature.profile.portal.professionalRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** Test double: [connectResult] decides what `connect` does. */
private class TestGitHubRepository(initial: GitHubAccount? = null) : GitHubRepository {
    val accountFlow = MutableStateFlow(initial)
    override val account: StateFlow<GitHubAccount?> = accountFlow
    var connectResult: (String) -> GitHubAccount = { GitHubAccount("octo-student", name = "Octo Student") }
    var disconnectError: Throwable? = null
    val connectedTokens = mutableListOf<String>()
    var disconnects = 0

    override suspend fun connect(token: String): GitHubAccount {
        connectedTokens += token
        return connectResult(token).also { accountFlow.value = it }
    }
    override suspend fun disconnect() {
        disconnects++
        disconnectError?.let { throw it }
        accountFlow.value = null
    }
    override suspend fun repo(fullName: String) = error("unused")
    override suspend fun ensureTaskBranch(fullName: String, branchName: String) = error("unused")
    override suspend fun tree(fullName: String, branch: String) = error("unused")
    override suspend fun file(fullName: String, branch: String, path: String) = error("unused")
    override suspend fun commit(fullName: String, branch: String, message: String, changes: List<FileChange>) = error("unused")
    override suspend fun openPullRequest(fullName: String, head: String, base: String, title: String, body: String) = error("unused")
}

private const val TOKEN = "test-token-not-real-0123456789-abcdefghij"

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectGitHubViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private suspend fun signedInSession() = SessionStore(InMemoryTokenStore()).apply { signedIn("t", FakeData.sam.copy(githubUsername = null)) }

    @Test fun connectSucceedsAndUpdatesSessionGithubUsername() = runTest {
        val repo = TestGitHubRepository()
        val session = signedInSession()
        val vm = ConnectGitHubViewModel(repo, session)
        vm.onTokenChange("  $TOKEN \n")
        vm.connect()
        assertTrue(vm.state.value.connecting)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(listOf(TOKEN), repo.connectedTokens) // trimmed
        assertFalse(s.connecting)
        assertTrue(s.isConnected)
        assertTrue(s.justConnected)
        assertEquals("Connected as @octo-student", s.connectedMessage)
        assertEquals("", s.token) // not kept in memory after success
        assertNull(s.error)
        assertEquals("octo-student", ((session.state.value as SessionState.SignedIn).user.githubUsername))
    }

    @Test fun unauthorizedShowsFriendlyMessageAndKeepsToken() = runTest {
        val repo = TestGitHubRepository().apply { connectResult = { throw GitHubException.Unauthorized() } }
        val session = signedInSession()
        val vm = ConnectGitHubViewModel(repo, session)
        vm.onTokenChange(TOKEN)
        vm.connect()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("That token didn't work. Check it was copied fully and hasn't expired.", s.error)
        assertEquals(TOKEN, s.token)
        assertFalse(s.isConnected)
        assertFalse(s.connecting)
        assertNull((session.state.value as SessionState.SignedIn).user.githubUsername)
    }

    @Test fun networkErrorKeepsTokenInputAndCanRetry() = runTest {
        val repo = TestGitHubRepository().apply { connectResult = { throw GitHubException.Network() } }
        val vm = ConnectGitHubViewModel(repo, signedInSession())
        vm.onTokenChange(TOKEN)
        vm.toggleTokenVisible()
        vm.connect()
        advanceUntilIdle()
        assertEquals("Couldn’t reach GitHub. Check your connection and try again.", vm.state.value.error)
        assertEquals(TOKEN, vm.state.value.token)
        assertTrue(vm.state.value.tokenVisible)
        assertTrue(vm.state.value.canConnect)

        repo.connectResult = { GitHubAccount("octo-student") }
        vm.connect()
        advanceUntilIdle()
        assertTrue(vm.state.value.isConnected)
        assertNull(vm.state.value.error)
    }

    @Test fun typingClearsError() = runTest {
        val repo = TestGitHubRepository().apply { connectResult = { throw GitHubException.RateLimited() } }
        val vm = ConnectGitHubViewModel(repo, signedInSession())
        vm.onTokenChange(TOKEN)
        vm.connect()
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        vm.onTokenChange(TOKEN + "x")
        assertNull(vm.state.value.error)
    }

    @Test fun localValidationDoesNotCallGitHub() = runTest {
        val repo = TestGitHubRepository()
        val vm = ConnectGitHubViewModel(repo, signedInSession())
        vm.connect()
        assertEquals("Paste your GitHub token first.", vm.state.value.error)
        vm.onTokenChange("github_pat_ abc def ghi jkl mno")
        vm.connect()
        assertNotNull(vm.state.value.error)
        vm.onTokenChange("short")
        vm.connect()
        assertNotNull(vm.state.value.error)
        advanceUntilIdle()
        assertTrue(repo.connectedTokens.isEmpty())
    }

    @Test fun disconnectAsksFirstThenDisconnects() = runTest {
        val repo = TestGitHubRepository(initial = GitHubAccount("octo-student"))
        val vm = ConnectGitHubViewModel(repo, signedInSession())
        advanceUntilIdle()
        assertTrue(vm.state.value.isConnected)

        vm.requestDisconnect()
        assertTrue(vm.state.value.showDisconnectConfirm)
        vm.cancelDisconnect()
        assertFalse(vm.state.value.showDisconnectConfirm)
        assertEquals(0, repo.disconnects)

        vm.requestDisconnect()
        vm.confirmDisconnect()
        advanceUntilIdle()
        assertEquals(1, repo.disconnects)
        assertFalse(vm.state.value.isConnected)
        assertFalse(vm.state.value.disconnecting)
        assertFalse(vm.state.value.showDisconnectConfirm)
    }

    @Test fun disconnectFailureShowsInlineError() = runTest {
        val repo = TestGitHubRepository(initial = GitHubAccount("octo-student")).apply { disconnectError = IllegalStateException("boom") }
        val vm = ConnectGitHubViewModel(repo, signedInSession())
        vm.requestDisconnect()
        vm.confirmDisconnect()
        advanceUntilIdle()
        assertTrue(vm.state.value.isConnected)
        assertEquals("Couldn’t disconnect GitHub. Try again.", vm.state.value.disconnectError)
    }

    @Test fun errorMessagesForEachFailure() {
        val m = ConnectGitHubViewModel.Companion
        assertTrue(m.connectErrorMessage(GitHubException.Forbidden()).contains("permissions"))
        assertTrue(m.connectErrorMessage(GitHubException.RateLimited()).contains("rate limit"))
        assertEquals("Couldn’t connect to GitHub. Try again.", m.connectErrorMessage(IllegalStateException()))
    }

    @Test fun profileShowsConnectedLoginFromAccountWithUsernameFallback() = runTest {
        val repo = TestGitHubRepository()
        val session = SessionStore(InMemoryTokenStore()).apply { signedIn("t", FakeData.sam.copy(githubUsername = "old-name")) }
        val profile = ProfileViewModel(session, gitHubAccount = repo.account)
        advanceUntilIdle()
        assertEquals("old-name", profile.state.value.gitHubLogin)

        val vm = ConnectGitHubViewModel(repo, session)
        vm.onTokenChange(TOKEN)
        vm.connect()
        advanceUntilIdle()
        assertEquals("octo-student", profile.state.value.gitHubLogin)
        assertEquals("octo-student", profile.state.value.user?.githubUsername)

        profile.openGitHub()
        advanceUntilIdle()
        assertTrue(profile.state.value.showingGitHub)
        profile.closeGitHub()
        advanceUntilIdle()
        assertFalse(profile.state.value.showingGitHub)
    }

    @Test fun resetClearsInputButKeepsAccount() = runTest {
        val repo = TestGitHubRepository(initial = GitHubAccount("octo-student"))
        val vm = ConnectGitHubViewModel(repo, signedInSession())
        vm.onTokenChange(TOKEN)
        vm.reset()
        advanceUntilIdle()
        assertEquals("", vm.state.value.token)
        assertTrue(vm.state.value.isConnected)
    }

    @Test fun portalGithubRowPrefersConnectedAccount() {
        val row = professionalRows(FakeData.sam.copy(githubUsername = "old-name"), gitHubLogin = "octo-student").first { it.label == "GitHub" }
        assertEquals("Connected · @octo-student", row.value)
        assertTrue(row.manageAction)
        val none = professionalRows(FakeData.sam.copy(githubUsername = null), gitHubLogin = null).first { it.label == "GitHub" }
        assertTrue(none.connectAction)
    }
}
