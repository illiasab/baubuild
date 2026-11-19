package com.vesper.baubuild.data.github

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeGitHubRepositoryTest {

    private val repoName = FakeGitHubRepository.LIBRARY_BOOKING

    @Test
    fun connectAcceptsAnyTokenExceptBad() = runTest {
        val gh = FakeGitHubRepository()
        assertFailsWith<GitHubException.Unauthorized> { gh.connect("bad") }
        assertFailsWith<GitHubException.Unauthorized> { gh.connect("  ") }
        assertNull(gh.account.value)
        assertEquals("demo-student", gh.connect("ghp_anything").login)
        assertEquals("demo-student", gh.account.value?.login)
        gh.disconnect()
        assertNull(gh.account.value)
    }

    @Test
    fun libraryBookingSampleHasTheDateBug() = runTest {
        val gh = FakeGitHubRepository()
        val paths = gh.tree(repoName, "main").map { it.path }
        assertEquals(
            listOf("src", "src/api", "src/api/bookings.js", "src/booking-form.js", "src/styles.css",
                "test", "test/booking-form.test.js", "package.json", "README.md"),
            paths,
        )
        val form = gh.file(repoName, "main", "src/booking-form.js").content
        assertTrue("form.date < today()" in form)
    }

    @Test
    fun otherReposGetAGenericProject() = runTest {
        val gh = FakeGitHubRepository()
        assertEquals(listOf("src", "src/index.js", "test", "test/index.test.js", "README.md"), gh.tree("someone/else", "main").map { it.path })
    }

    @Test
    fun branchCommitAndPullRequestFlow() = runTest {
        val gh = FakeGitHubRepository()
        val branchName = taskBranchName("t1", "Reject past dates in the booking form")
        val branch = gh.ensureTaskBranch(repoName, branchName)
        assertEquals("main", branch.baseBranch)
        assertEquals(branch, gh.ensureTaskBranch(repoName, branchName)) // idempotent

        val result = gh.commit(
            repoName, branchName, "Compare dates, not strings",
            listOf(FileChange("src/booking-form.js", "fixed"), FileChange("src/styles.css", null), FileChange("src/new.js", "new")),
        )
        assertEquals(40, result.sha.length)
        assertEquals("https://github.com/$repoName/commit/${result.sha}", result.url)
        assertEquals("fixed", gh.file(repoName, branchName, "src/booking-form.js").content)
        assertFailsWith<GitHubException.NotFound> { gh.file(repoName, branchName, "src/styles.css") }
        assertEquals(result.sha, gh.ensureTaskBranch(repoName, branchName).headSha)

        // main is untouched
        assertTrue("form.date < today()" in gh.file(repoName, "main", "src/booking-form.js").content)
        assertFalse(gh.tree(repoName, "main").any { it.path == "src/new.js" })

        val pr = gh.openPullRequest(repoName, branchName, "main", "Fix past dates", "")
        assertEquals(PullRequest(1, "https://github.com/$repoName/pull/1", "Fix past dates"), pr)
        assertEquals(pr, gh.openPullRequest(repoName, branchName, "main", "Again", ""))

        gh.ensureTaskBranch(repoName, "task/t2-other")
        assertEquals(2, gh.openPullRequest(repoName, "task/t2-other", "main", "Other", "").number)
    }

    @Test
    fun unknownBranchIsNotFound() = runTest {
        val gh = FakeGitHubRepository()
        assertFailsWith<GitHubException.NotFound> { gh.tree(repoName, "nope") }
        assertFailsWith<GitHubException.NotFound> { gh.commit(repoName, "nope", "m", listOf(FileChange("a", "b"))) }
    }

    @Test
    fun taskBranchNameIsSlugged() {
        assertEquals("task/t1-reject-past-dates-in-the-booking-form", taskBranchName("T1", "Reject past dates in the booking form!"))
        assertEquals("task/t2-fix-ui-bug", taskBranchName("t2", "  Fix -- UI   bug?? "))
        val long = taskBranchName("t3", "A".repeat(100))
        assertEquals("task/t3-" + "a".repeat(40), long)
        assertTrue(taskBranchName("t/4 x", "x").startsWith("task/t4x-"))
    }
}
