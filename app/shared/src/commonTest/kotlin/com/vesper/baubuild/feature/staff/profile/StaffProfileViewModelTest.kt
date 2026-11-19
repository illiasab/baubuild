package com.vesper.baubuild.feature.staff.profile

import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.*
import com.vesper.baubuild.core.notifications.NotificationPermission
import com.vesper.baubuild.core.notifications.NotificationPermissionStatus
import com.vesper.baubuild.core.notifications.NotificationPermissionStatus.*
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.repository.StaffRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/** List items as `GET /v1/senior-engineers` might send them: no bio or skills yet. */
private val iliasItem = SeniorEngineer(id = "eng-ilias", name = "Ilias", title = "Tech Lead Engineer", bio = "", email = "ilias@bau.edu")
private val arianItem = SeniorEngineer(id = "eng-arian", name = "Arian", title = "Senior Engineer", bio = "")

private val nora = StaffProfile(
    department = "Computer Science", title = "Professor", classes = listOf("CMPS433", "CMPS210"),
    projectsPosted = 12, delivered = 9, raisedUsd = 4820, seniorEngineers = listOf(iliasItem, arianItem),
    notifyWhenDone = true, notifyWhenStudentJoins = false,
)

private class FakeProfileStaffRepo(var current: StaffProfile = nora) : StaffRepository {
    var failSaves = false
    var failEngineer = false
    val engineerCalls = mutableListOf<String>()
    var engineerGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun seniorEngineer(id: String): SeniorEngineer {
        engineerCalls += id
        engineerGate?.await()
        if (failEngineer) error("offline")
        // Public profile: has bio + skills, lacks title and email.
        return SeniorEngineer(id = id, name = "Ilias", title = "", bio = "Leads BAU BUILD engineering.", skills = listOf("Kotlin", "Go"),
            githubUsername = "illiasab")
    }
    val saves = mutableListOf<Pair<Boolean, Boolean>>()

    override suspend fun profile() = current
    override suspend fun updateNotifications(notifyWhenDone: Boolean, notifyWhenStudentJoins: Boolean): StaffProfile {
        saves += notifyWhenDone to notifyWhenStudentJoins
        if (failSaves) error("offline")
        current = current.copy(notifyWhenDone = notifyWhenDone, notifyWhenStudentJoins = notifyWhenStudentJoins)
        return current
    }

    override suspend fun overview(): StaffOverview = TODO()
    override suspend fun inquiries(): List<StaffInquiry> = TODO()
    override suspend fun inquiry(id: String): StaffInquiry = TODO()
    override suspend fun refine(draft: InquiryDraft): RefinedInquiry = TODO()
    override suspend fun post(draft: InquiryDraft, refined: RefinedInquiry): StaffInquiry = TODO()
    override suspend fun saveDraft(draft: InquiryDraft): StaffInquiry = TODO()
    override suspend fun updateDetails(id: String, draft: InquiryDraft): StaffInquiry = TODO()
    override suspend fun cancel(id: String): StaffInquiry = TODO()
    override suspend fun resolveBlocker(inquiryId: String, blockerId: String): StaffInquiry = TODO()
    override suspend fun worldProjects(): List<WorldProject> = TODO()
}

private class FakePermission(var status: NotificationPermissionStatus, private val grantOnRequest: Boolean = true) : NotificationPermission {
    var requests = 0
    var settingsOpened = 0
    override suspend fun status() = status
    override suspend fun request(): Boolean {
        requests++
        if (status == NotDetermined) status = if (grantOnRequest) Granted else Denied
        return status == Granted
    }
    override fun openSystemSettings() { settingsOpened++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StaffProfileViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val staffUser = FakeData.sam.copy(displayName = "Nora Hale", email = "nora.hale@bau.edu", role = Role.Staff)

    private suspend fun vm(repo: StaffRepository, permission: NotificationPermission? = null): StaffProfileViewModel {
        val session = SessionStore(InMemoryTokenStore()).apply { signedIn("t", staffUser) }
        return StaffProfileViewModel(session, repo).also { vm -> permission?.let(vm::bindPermission) }
    }

    @Test fun showsProfessorDetails() = runTest {
        val vm = vm(FakeProfileStaffRepo(), FakePermission(Granted))
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Dr. Nora Hale", s.displayName)
        assertEquals("Professor · Computer Science", s.subtitle)
        assertEquals("CMPS433, CMPS210", s.classesLabel)
        assertEquals("Ilias, Arian", s.seniorEngineersLabel)
        assertTrue(s.isOn(StaffNotification.ProjectDone))
        assertFalse(s.isOn(StaffNotification.StudentJoins))
        assertFalse(s.notificationsBlocked)
    }

    @Test fun noClassesForNonProfessors() = runTest {
        val vm = vm(FakeProfileStaffRepo(nora.copy(title = "Administrator", classes = emptyList())))
        advanceUntilIdle()
        assertEquals("No classes", vm.state.value.classesLabel)
        assertEquals("Nora Hale", vm.state.value.displayName)
    }

    @Test fun turningOnAsksPermissionAndSavesWhenGranted() = runTest {
        val repo = FakeProfileStaffRepo()
        val permission = FakePermission(NotDetermined, grantOnRequest = true)
        val vm = vm(repo, permission)
        advanceUntilIdle()
        vm.setNotification(StaffNotification.StudentJoins, true)
        advanceUntilIdle()
        assertEquals(1, permission.requests)
        assertEquals(listOf(true to true), repo.saves)
        assertTrue(vm.state.value.isOn(StaffNotification.StudentJoins))
        assertEquals(Granted, vm.state.value.permission)
        assertNull(vm.state.value.busy)
    }

    @Test fun deniedPermissionKeepsToggleOffAndShowsSettingsHint() = runTest {
        val repo = FakeProfileStaffRepo()
        val permission = FakePermission(NotDetermined, grantOnRequest = false)
        val vm = vm(repo, permission)
        advanceUntilIdle()
        vm.setNotification(StaffNotification.StudentJoins, true)
        advanceUntilIdle()
        assertEquals(1, permission.requests)
        assertTrue(repo.saves.isEmpty())
        val s = vm.state.value
        assertFalse(s.isOn(StaffNotification.StudentJoins))
        assertTrue(s.notificationsBlocked)
        // Saved prefs are kept but shown off while the OS blocks notifications.
        assertFalse(s.isOn(StaffNotification.ProjectDone))
        vm.openNotificationSettings()
        assertEquals(1, permission.settingsOpened)
    }

    @Test fun alreadyDeniedDoesNotPromptAgain() = runTest {
        val repo = FakeProfileStaffRepo()
        val permission = FakePermission(Denied)
        val vm = vm(repo, permission)
        advanceUntilIdle()
        assertTrue(vm.state.value.notificationsBlocked)
        vm.setNotification(StaffNotification.StudentJoins, true)
        advanceUntilIdle()
        assertEquals(0, permission.requests)
        assertTrue(repo.saves.isEmpty())
    }

    @Test fun grantingInSettingsRestoresTogglesOnResume() = runTest {
        val permission = FakePermission(Denied)
        val vm = vm(FakeProfileStaffRepo(), permission)
        advanceUntilIdle()
        assertFalse(vm.state.value.isOn(StaffNotification.ProjectDone))
        permission.status = Granted
        vm.refreshPermission()
        advanceUntilIdle()
        assertTrue(vm.state.value.isOn(StaffNotification.ProjectDone))
    }

    @Test fun turningOffSavesWithoutPermissionPrompt() = runTest {
        val repo = FakeProfileStaffRepo()
        val permission = FakePermission(NotDetermined)
        val vm = vm(repo, permission)
        advanceUntilIdle()
        vm.setNotification(StaffNotification.ProjectDone, false)
        advanceUntilIdle()
        assertEquals(0, permission.requests)
        assertEquals(listOf(false to false), repo.saves)
        assertFalse(vm.state.value.isOn(StaffNotification.ProjectDone))
    }

    @Test fun saveFailureRevertsToggleWithInlineError() = runTest {
        val repo = FakeProfileStaffRepo().apply { failSaves = true }
        val vm = vm(repo, FakePermission(Granted))
        advanceUntilIdle()
        vm.setNotification(StaffNotification.StudentJoins, true)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(1, repo.saves.size)
        assertFalse(s.isOn(StaffNotification.StudentJoins))
        assertTrue(s.isOn(StaffNotification.ProjectDone))
        assertNotNull(s.notificationError)
        assertIs<LoadState.Content<StaffProfile>>(s.profile)

        repo.failSaves = false
        vm.setNotification(StaffNotification.StudentJoins, true)
        advanceUntilIdle()
        assertTrue(vm.state.value.isOn(StaffNotification.StudentJoins))
        assertNull(vm.state.value.notificationError)
    }

    @Test fun openingEngineerShowsListDataThenFullProfile() = runTest {
        val repo = FakeProfileStaffRepo().apply { engineerGate = kotlinx.coroutines.CompletableDeferred() }
        val vm = vm(repo)
        advanceUntilIdle()
        assertEquals(listOf(iliasItem, arianItem), vm.state.value.seniorEngineers)
        vm.openSeniorEngineer(iliasItem)
        runCurrent()
        // Shown immediately, before the backend answers.
        val loading = assertNotNull(vm.state.value.engineerSheet)
        assertEquals(iliasItem, loading.engineer)
        assertTrue(loading.loading)
        repo.engineerGate!!.complete(Unit)
        advanceUntilIdle()
        val sheet = assertNotNull(vm.state.value.engineerSheet)
        assertFalse(sheet.loading)
        assertNull(sheet.error)
        assertEquals("Leads BAU BUILD engineering.", sheet.engineer.bio)
        assertEquals(listOf("Kotlin", "Go"), sheet.engineer.skills)
        assertEquals("illiasab", sheet.engineer.githubUsername)
        // Missing in the public profile → kept from the list.
        assertEquals("Tech Lead Engineer", sheet.engineer.title)
        assertEquals("ilias@bau.edu", sheet.engineer.email)
        assertEquals(listOf("eng-ilias"), repo.engineerCalls)

        vm.closeSeniorEngineer()
        runCurrent()
        assertNull(vm.state.value.engineerSheet)
        // Reopening shows the full profile at once (and refreshes quietly).
        repo.engineerGate = kotlinx.coroutines.CompletableDeferred()
        vm.openSeniorEngineer(iliasItem)
        runCurrent()
        assertEquals("Leads BAU BUILD engineering.", vm.state.value.engineerSheet?.engineer?.bio)
        vm.closeSeniorEngineer()
        repo.engineerGate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.state.value.engineerSheet) // a late answer never reopens it
    }

    @Test fun engineerDetailFailureKeepsListDataWithInlineError() = runTest {
        val repo = FakeProfileStaffRepo().apply { failEngineer = true }
        val vm = vm(repo)
        advanceUntilIdle()
        vm.openSeniorEngineer(arianItem)
        advanceUntilIdle()
        val sheet = assertNotNull(vm.state.value.engineerSheet)
        assertEquals(arianItem, sheet.engineer)
        assertFalse(sheet.loading)
        assertNotNull(sheet.error)
        assertIs<LoadState.Content<StaffProfile>>(vm.state.value.profile)
    }

    @Test fun noEngineersLabel() = runTest {
        val vm = vm(FakeProfileStaffRepo(nora.copy(seniorEngineers = emptyList())))
        advanceUntilIdle()
        assertTrue(vm.state.value.seniorEngineers.isEmpty())
        assertEquals("None yet", vm.state.value.seniorEngineersLabel)
    }

    @Test fun roleNoteMentionsInterviews() {
        assertEquals("Notified when your projects are done · Runs Lead interviews", iliasItem.roleNote())
        assertEquals("Notified when your projects are done", iliasItem.copy(isInterviewer = false).roleNote())
        assertEquals("https://github.com/illiasab", githubUrl("@illiasab"))
    }

    @Test fun formatsRaisedMoney() {
        assertEquals("$4,820", formatUsd(4820))
        assertEquals("$0", formatUsd(0))
        assertEquals("$1,000,000", formatUsd(1_000_000))
    }
}
