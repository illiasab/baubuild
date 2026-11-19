package com.vesper.baubuild.data.github

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * In-memory GitHub used while the backend is fake. Every repo starts with a realistic sample project
 * (`bau-build/library-booking` has the booking-form date bug the demo task is about; any other repo gets a
 * small generic project). Branches are copies of the default branch; commits change only that branch;
 * PRs are numbered from 1 per repo.
 *
 * `connect`: any non-blank token works and signs in as `demo-student`, except `"bad"` → [GitHubException.Unauthorized].
 */
class FakeGitHubRepository(
    /** Simulated network latency per call (ms). Tests run on virtual time, so this costs nothing there. */
    private val latency: LongRange = 200L..600L,
    private val random: Random = Random.Default,
) : GitHubRepository {

    private class Branch(val files: MutableMap<String, String>, var headSha: String)
    private class Repo(val defaultBranch: String, val branches: MutableMap<String, Branch>, val pulls: MutableList<Pr>)
    private data class Pr(val number: Int, val head: String, val base: String, val title: String, val url: String)

    private val lock = Mutex()
    private val repos = mutableMapOf<String, Repo>()

    private val _account = MutableStateFlow<GitHubAccount?>(null)
    override val account: StateFlow<GitHubAccount?> = _account.asStateFlow()

    override suspend fun connect(token: String): GitHubAccount {
        simulateLatency()
        val trimmed = token.trim()
        if (trimmed.isEmpty() || trimmed == "bad") throw GitHubException.Unauthorized()
        return GitHubAccount(login = "demo-student", name = "Demo Student").also { _account.value = it }
    }

    override suspend fun disconnect() {
        _account.value = null
    }

    override suspend fun repo(fullName: String): GitHubRepoInfo {
        simulateLatency()
        return lock.withLock { GitHubRepoInfo(fullName, repoLocked(fullName).defaultBranch) }
    }

    override suspend fun ensureTaskBranch(fullName: String, branchName: String): TaskBranch {
        simulateLatency()
        return lock.withLock {
            val repo = repoLocked(fullName)
            val branch = repo.branches.getOrPut(branchName) {
                val base = repo.branches.getValue(repo.defaultBranch)
                Branch(base.files.toMutableMap(), base.headSha)
            }
            TaskBranch(fullName, branchName, repo.defaultBranch, branch.headSha)
        }
    }

    override suspend fun tree(fullName: String, branch: String): List<RepoEntry> {
        simulateLatency()
        return lock.withLock {
            val files = branchLocked(fullName, branch).files
            val dirs = files.keys.flatMap { path ->
                val parts = path.split('/')
                (1 until parts.size).map { parts.take(it).joinToString("/") }
            }.toSet()
            (dirs.map { RepoEntry(it, RepoEntryType.Directory) } +
                files.map { (path, content) -> RepoEntry(path, RepoEntryType.File, content.encodeToByteArray().size.toLong(), fakeSha(content)) })
                .sortedWith(RepoExplorerOrder)
        }
    }

    override suspend fun file(fullName: String, branch: String, path: String): RepoFile {
        simulateLatency()
        return lock.withLock {
            val content = branchLocked(fullName, branch).files[path] ?: throw GitHubException.NotFound("File $path")
            RepoFile(path, content, fakeSha(content))
        }
    }

    override suspend fun commit(fullName: String, branch: String, message: String, changes: List<FileChange>): CommitResult {
        simulateLatency()
        if (changes.isEmpty()) throw GitHubException.Conflict("There are no changes to commit.")
        return lock.withLock {
            val target = branchLocked(fullName, branch)
            changes.forEach { change ->
                if (change.content == null) target.files.remove(change.path) else target.files[change.path] = change.content
            }
            val sha = randomSha()
            target.headSha = sha
            CommitResult(sha, "https://github.com/$fullName/commit/$sha", message)
        }
    }

    override suspend fun openPullRequest(fullName: String, head: String, base: String, title: String, body: String): PullRequest {
        simulateLatency()
        return lock.withLock {
            val repo = repoLocked(fullName)
            if (head !in repo.branches) throw GitHubException.NotFound("Branch $head")
            if (base !in repo.branches) throw GitHubException.NotFound("Branch $base")
            val pr = repo.pulls.firstOrNull { it.head == head && it.base == base } ?: run {
                val number = repo.pulls.size + 1
                Pr(number, head, base, title, "https://github.com/$fullName/pull/$number").also { repo.pulls += it }
            }
            PullRequest(pr.number, pr.url, pr.title)
        }
    }

    // ---- helpers (call with [lock] held) ----

    private fun repoLocked(fullName: String): Repo = repos.getOrPut(fullName) {
        val files = if (fullName.equals(LIBRARY_BOOKING, ignoreCase = true)) libraryBookingFiles() else genericFiles(fullName)
        Repo("main", mutableMapOf("main" to Branch(files.toMutableMap(), randomSha())), mutableListOf())
    }

    private fun branchLocked(fullName: String, branch: String): Branch =
        repoLocked(fullName).branches[branch] ?: throw GitHubException.NotFound("Branch $branch")

    private suspend fun simulateLatency() {
        if (!latency.isEmpty() && latency.last > 0) delay(random.nextLong(latency.first, latency.last + 1))
    }

    private fun randomSha(): String = buildString { repeat(40) { append(HEX[random.nextInt(16)]) } }

    private fun fakeSha(content: String): String {
        var h = 1125899906842597L
        content.forEach { h = 31 * h + it.code }
        return (h.toULong().toString(16).padStart(16, '0')).repeat(3).take(40)
    }

    companion object {
        const val LIBRARY_BOOKING = "bau-build/library-booking"
        private const val HEX = "0123456789abcdef"

        private fun libraryBookingFiles(): Map<String, String> = mapOf(
            "package.json" to """
                |{
                |  "name": "library-booking",
                |  "version": "0.3.0",
                |  "private": true,
                |  "description": "Room booking for the BAU library study rooms",
                |  "type": "module",
                |  "scripts": {
                |    "start": "vite",
                |    "test": "vitest run"
                |  },
                |  "devDependencies": {
                |    "vite": "^5.4.0",
                |    "vitest": "^2.1.0"
                |  }
                |}
                |""".trimMargin(),
            "README.md" to """
                |# Library Booking
                |
                |Book a study room in the BAU library. Students pick a room, a date and a time slot.
                |
                |## Run it
                |
                |```bash
                |npm install
                |npm start
                |npm test
                |```
                |
                |## Project layout
                |
                |- `src/booking-form.js` – form validation and submission
                |- `src/api/bookings.js` – calls to the bookings API
                |- `src/styles.css` – form styles
                |- `test/` – unit tests (Vitest)
                |""".trimMargin(),
            "src/booking-form.js" to """
                |import { createBooking } from './api/bookings.js';
                |
                |const ROOMS = ['A101', 'A102', 'B201', 'Quiet Room'];
                |
                |/** Today's date in the same format the date input gives us (DD/MM/YYYY). */
                |function today() {
                |  return new Date().toLocaleDateString('en-GB');
                |}
                |
                |/**
                | * Validates the booking form.
                | * @param {{ room: string, date: string, slot: string }} form  date is DD/MM/YYYY
                | * @returns {string[]} error messages, empty when the form is valid
                | */
                |export function validateBooking(form) {
                |  const errors = [];
                |  if (!ROOMS.includes(form.room)) {
                |    errors.push('Choose a room.');
                |  }
                |  if (!form.date) {
                |    errors.push('Choose a date.');
                |  } else if (form.date < today()) {
                |    errors.push('You can’t book a date in the past.');
                |  }
                |  if (!form.slot) {
                |    errors.push('Choose a time slot.');
                |  }
                |  return errors;
                |}
                |
                |export async function submitBooking(form) {
                |  const errors = validateBooking(form);
                |  if (errors.length > 0) {
                |    return { ok: false, errors };
                |  }
                |  const booking = await createBooking(form);
                |  return { ok: true, booking };
                |}
                |""".trimMargin(),
            "src/api/bookings.js" to """
                |const BASE_URL = '/api/bookings';
                |
                |export async function listBookings(room) {
                |  const res = await fetch(`${'$'}{BASE_URL}?room=${'$'}{encodeURIComponent(room)}`);
                |  if (!res.ok) throw new Error(`Failed to load bookings (${'$'}{res.status})`);
                |  return res.json();
                |}
                |
                |export async function createBooking(form) {
                |  const res = await fetch(BASE_URL, {
                |    method: 'POST',
                |    headers: { 'Content-Type': 'application/json' },
                |    body: JSON.stringify(form),
                |  });
                |  if (!res.ok) throw new Error(`Booking failed (${'$'}{res.status})`);
                |  return res.json();
                |}
                |""".trimMargin(),
            "src/styles.css" to """
                |.booking-form {
                |  display: grid;
                |  gap: 12px;
                |  max-width: 420px;
                |  font-family: system-ui, sans-serif;
                |}
                |
                |.booking-form label {
                |  font-weight: 600;
                |}
                |
                |.booking-form .error {
                |  color: #c62828;
                |  font-size: 0.9rem;
                |}
                |""".trimMargin(),
            "test/booking-form.test.js" to """
                |import { describe, it, expect } from 'vitest';
                |import { validateBooking } from '../src/booking-form.js';
                |
                |function format(date) {
                |  return date.toLocaleDateString('en-GB');
                |}
                |
                |describe('validateBooking', () => {
                |  it('accepts a booking for tomorrow', () => {
                |    const tomorrow = new Date(Date.now() + 24 * 60 * 60 * 1000);
                |    expect(validateBooking({ room: 'A101', date: format(tomorrow), slot: '10:00' })).toEqual([]);
                |  });
                |
                |  it('rejects a booking last month', () => {
                |    const lastMonth = new Date();
                |    lastMonth.setMonth(lastMonth.getMonth() - 1);
                |    expect(validateBooking({ room: 'A101', date: format(lastMonth), slot: '10:00' }))
                |      .toContain('You can’t book a date in the past.');
                |  });
                |
                |  it('requires a room', () => {
                |    expect(validateBooking({ room: '', date: '', slot: '' })).toContain('Choose a room.');
                |  });
                |});
                |""".trimMargin(),
        )

        private fun genericFiles(fullName: String): Map<String, String> {
            val name = fullName.substringAfter('/')
            return mapOf(
                "README.md" to "# $name\n\nStarter project for the BAU BUILD course.\n\n```bash\nnpm test\n```\n",
                "src/index.js" to "export function greet(name) {\n  return `Hello, ${'$'}{name}!`;\n}\n",
                "test/index.test.js" to """
                    |import { it, expect } from 'vitest';
                    |import { greet } from '../src/index.js';
                    |
                    |it('greets by name', () => {
                    |  expect(greet('BAU')).toBe('Hello, BAU!');
                    |});
                    |""".trimMargin(),
            )
        }
    }
}
