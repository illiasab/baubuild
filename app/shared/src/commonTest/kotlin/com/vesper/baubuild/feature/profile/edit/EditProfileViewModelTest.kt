package com.vesper.baubuild.feature.profile.edit

import com.vesper.baubuild.core.media.LocalImageStore
import com.vesper.baubuild.core.media.PickedImage
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.core.session.InMemoryTokenStore
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.core.session.SessionStore
import com.vesper.baubuild.data.fake.FakeData
import com.vesper.baubuild.data.fake.FakeProfileRepository
import com.vesper.baubuild.data.repository.ProfileRepository
import com.vesper.baubuild.data.repository.ProfileUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val sam = FakeData.sam.copy(
        displayName = "Sam Carter", bio = "Builder", skills = listOf("JavaScript", "Python"),
        classCodes = listOf("CMPS433"), githubUsername = null, avatarUrl = null,
    )

    /** Records calls; fails while [failing] is true. */
    private class RecordingRepo(private val session: SessionStore) : ProfileRepository {
        var failing = false
        val updates = mutableListOf<ProfileUpdate>()
        var uploads = 0
        override suspend fun updateProfile(update: ProfileUpdate): User {
            if (failing) error("503")
            updates += update
            val u = (session.state.value as SessionState.SignedIn).user
            return u.copy(
                displayName = update.displayName, bio = update.bio, skills = update.skills,
                githubUsername = update.githubUsername.ifBlank { null },
                avatarUrl = if (update.removeAvatar) null else update.avatarUrl ?: u.avatarUrl,
            )
        }
        override suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String {
            uploads++
            return "https://cdn.example/avatar/$uploads.jpg"
        }
    }

    private suspend fun TestScope.setup(user: User = sam): Triple<SessionStore, RecordingRepo, EditProfileViewModel> {
        val session = SessionStore(InMemoryTokenStore()).apply { signedIn("t", user) }
        val repo = RecordingRepo(session)
        val vm = EditProfileViewModel(session, repo)
        advanceUntilIdle()
        return Triple(session, repo, vm)
    }

    private fun EditProfileViewModel.s() = state.value
    private fun signedInUser(session: SessionStore) = (session.state.value as SessionState.SignedIn).user

    @Test fun startsFromSessionUserAndIsNotDirty() = runTest {
        val (_, _, vm) = setup()
        assertEquals("Sam Carter", vm.s().displayName)
        assertEquals(listOf("JavaScript", "Python"), vm.s().skills)
        assertFalse(vm.s().isDirty)
        assertFalse(vm.s().canSave)
    }

    @Test fun dirtyTracking() = runTest {
        val (_, _, vm) = setup()
        vm.onBioChange("New bio")
        assertTrue(vm.s().isDirty)
        vm.onBioChange("Builder  ") // trims back to original
        assertFalse(vm.s().isDirty)
        vm.onNameChange("  Sam   Carter ") // whitespace normalized
        assertFalse(vm.s().isDirty)
        vm.onGithubChange("@samc")
        assertTrue(vm.s().isDirty)
        vm.onGithubChange("")
        assertFalse(vm.s().isDirty)
        vm.onPhotoPicked(PickedImage(byteArrayOf(1, 2, 3), "image/jpeg"))
        assertTrue(vm.s().isDirty)
        vm.removePhoto() // no original photo → back to unchanged
        assertEquals(PhotoEdit.Unchanged, vm.s().photo)
        assertFalse(vm.s().isDirty)
        vm.onPhotoPicked(null) // cancelled picker changes nothing
        assertFalse(vm.s().isDirty)
    }

    @Test fun removingExistingPhotoIsAChange() = runTest {
        val (_, _, vm) = setup(sam.copy(avatarUrl = "https://cdn.example/a.jpg"))
        assertTrue(vm.s().hasPhoto)
        vm.removePhoto()
        assertEquals(PhotoEdit.Removed, vm.s().photo)
        assertFalse(vm.s().hasPhoto)
        assertTrue(vm.s().canSave)
    }

    @Test fun validation() = runTest {
        val (_, _, vm) = setup()
        vm.onNameChange("S")
        assertNotNull(vm.s().nameError)
        assertFalse(vm.s().canSave)
        vm.onNameChange("Sam C")
        assertNull(vm.s().nameError)
        assertTrue(vm.s().canSave)

        vm.onGithubChange("bad name!")
        assertNotNull(vm.s().githubError)
        assertFalse(vm.s().canSave)
        vm.onGithubChange("-sam")
        assertNotNull(vm.s().githubError)
        vm.onGithubChange("https://github.com/sam-carter/")
        assertEquals("sam-carter", vm.s().normalizedGithub)
        assertNull(vm.s().githubError)

        vm.onBioChange("x".repeat(400))
        assertEquals(EditProfileRules.MAX_BIO, vm.s().bio.length)
        assertEquals(0, vm.s().bioRemaining)
    }

    @Test fun skillsAddRemoveDedupeAndLimit() = runTest {
        val (_, _, vm) = setup()
        vm.onSkillDraftChange("  kotlin ")
        vm.commitSkillDraft()
        assertEquals(listOf("JavaScript", "Python", "kotlin"), vm.s().skills)
        assertEquals("", vm.s().skillDraft)

        vm.onSkillDraftChange("PYTHON")
        vm.commitSkillDraft() // duplicate (case-insensitive) is swallowed
        assertEquals(3, vm.s().skills.size)
        assertEquals("", vm.s().skillDraft)

        vm.onSkillDraftChange("Go,SQL,")
        assertEquals(listOf("JavaScript", "Python", "kotlin", "Go", "SQL"), vm.s().skills)

        vm.removeSkill("Python")
        assertEquals(listOf("JavaScript", "kotlin", "Go", "SQL"), vm.s().skills)
        assertFalse("Kotlin" in vm.s().skillSuggestions) // already added as "kotlin"
        assertTrue("Swift" in vm.s().skillSuggestions)

        listOf("A1", "A2", "A3", "A4", "A5", "A6").forEach { vm.addSkill(it) }
        assertEquals(EditProfileRules.MAX_SKILLS, vm.s().skills.size)
        assertFalse(vm.addSkill("Rust"))
        assertNotNull(vm.s().skillError)
        assertTrue(vm.s().skillSuggestions.isEmpty())
    }

    @Test fun saveSuccessUpdatesSessionAndFinishes() = runTest {
        val (session, repo, vm) = setup()
        val photo = PickedImage(byteArrayOf(9, 9), "image/jpeg")
        vm.onNameChange("Sam Carter Jr")
        vm.onBioChange("Shipping things")
        vm.onGithubChange("@samc")
        vm.addSkill("Kotlin")
        vm.onPhotoPicked(photo)
        vm.save()
        assertTrue(vm.s().saving)
        advanceUntilIdle()

        assertFalse(vm.s().saving)
        assertTrue(vm.s().finished)
        val u = signedInUser(session)
        assertEquals("Sam Carter Jr", u.displayName)
        assertEquals("Shipping things", u.bio)
        assertEquals("samc", u.githubUsername)
        assertEquals(listOf("JavaScript", "Python", "Kotlin"), u.skills)
        assertEquals(listOf("CMPS433"), u.classCodes)
        assertEquals("https://cdn.example/avatar/1.jpg", u.avatarUrl)
        assertSame(photo.bytes, LocalImageStore.get(u.avatarUrl!!)) // renders immediately
        assertEquals(1, repo.uploads)
    }

    @Test fun saveFailureKeepsInputAndRetryDoesNotReupload() = runTest {
        val (session, repo, vm) = setup()
        repo.failing = true
        vm.onBioChange("Unsaved bio")
        vm.onPhotoPicked(PickedImage(byteArrayOf(1), "image/jpeg"))
        vm.save()
        advanceUntilIdle()

        assertNotNull(vm.s().error)
        assertFalse(vm.s().saving)
        assertFalse(vm.s().finished)
        assertEquals("Unsaved bio", vm.s().bio)
        assertTrue(vm.s().isDirty)
        assertEquals("Builder", signedInUser(session).bio)

        repo.failing = false
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.s().finished)
        assertEquals("Unsaved bio", signedInUser(session).bio)
        assertEquals(1, repo.uploads)
    }

    @Test fun removePhotoSendsRemoveFlag() = runTest {
        val (session, repo, vm) = setup(sam.copy(avatarUrl = "https://cdn.example/a.jpg"))
        vm.removePhoto()
        vm.save()
        advanceUntilIdle()
        assertTrue(repo.updates.single().removeAvatar)
        assertNull(signedInUser(session).avatarUrl)
    }

    @Test fun closeAsksOnlyWhenDirty() = runTest {
        val (_, _, vm) = setup()
        vm.requestClose()
        assertTrue(vm.s().finished)

        val (_, _, vm2) = setup()
        vm2.onBioChange("changed")
        vm2.requestClose()
        assertTrue(vm2.s().showDiscardConfirm)
        assertFalse(vm2.s().finished)
        vm2.keepEditing()
        assertFalse(vm2.s().showDiscardConfirm)
        assertEquals("changed", vm2.s().bio)
        vm2.requestClose()
        vm2.discardChanges()
        assertTrue(vm2.s().finished)
    }

    @Test fun fakeRepositoryStoresAvatarForDisplay() = runTest {
        val repo = FakeProfileRepository { sam }
        val bytes = byteArrayOf(4, 2)
        val url = repo.uploadAvatar(bytes, "image/png")
        assertTrue(url.startsWith("memory://"))
        assertSame(bytes, LocalImageStore.get(url))
        val updated = repo.updateProfile(ProfileUpdate("Sam", "b", listOf("Go"), githubUsername = "", avatarUrl = url))
        assertEquals(url, updated.avatarUrl)
        assertNull(updated.githubUsername)
        assertEquals(sam.classCodes, updated.classCodes)
    }
}
