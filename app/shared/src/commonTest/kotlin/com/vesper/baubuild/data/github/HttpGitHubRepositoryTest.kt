package com.vesper.baubuild.data.github

import com.vesper.baubuild.core.session.TokenStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpGitHubRepositoryTest {

    private class SeededStore(private var value: String?) : TokenStore {
        override suspend fun read() = value
        override suspend fun write(token: String) { value = token }
        override suspend fun clear() { value = null }
    }

    private class Recorded(val method: String, val path: String, val query: String, val body: JsonObject?, val auth: String?)

    private class Harness(
        token: String? = "tok",
        val handler: MockRequestHandleScope.(method: String, path: String, req: HttpRequestData) -> HttpResponseData,
    ) {
        val store = SeededStore(token)
        val calls = mutableListOf<Recorded>()
        val engine = MockEngine { req ->
            val body = (req.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
            calls += Recorded(req.method.value, req.url.encodedPath, req.url.encodedQuery, body, req.headers[HttpHeaders.Authorization])
            handler(req.method.value, req.url.encodedPath, req)
        }
        val repo = HttpGitHubRepository(store, engine, restoreOnInit = false)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg headers: Pair<String, String>) =
        respond(
            body,
            status,
            headersOf(*(headers.map { it.first to listOf(it.second) } + (HttpHeaders.ContentType to listOf("application/json"))).toTypedArray()),
        )

    @Test
    fun connectStoresTokenAndPublishesAccount() = runTest {
        val h = Harness(token = null) { _, path, _ ->
            assertEquals("/user", path)
            json("""{"login":"octo","name":"Octo Cat","avatar_url":"https://a/x.png"}""")
        }
        val account = h.repo.connect("  ghp_abc ")
        assertEquals(GitHubAccount("octo", "Octo Cat", "https://a/x.png"), account)
        assertEquals(account, h.repo.account.value)
        assertEquals("ghp_abc", h.store.read())
        val call = h.calls.single()
        assertEquals("Bearer ghp_abc", call.auth)
        val headers = h.engine.requestHistory.single().headers
        assertEquals("application/vnd.github+json", headers[HttpHeaders.Accept])
        assertEquals("2022-11-28", headers["X-GitHub-Api-Version"])
    }

    @Test
    fun connectWithBadTokenThrowsUnauthorizedAndStoresNothing() = runTest {
        val h = Harness(token = null) { _, _, _ -> json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }
        assertFailsWith<GitHubException.Unauthorized> { h.repo.connect("nope") }
        assertNull(h.store.read())
        assertNull(h.repo.account.value)
    }

    @Test
    fun restoreLoadsAccountAndClearsTokenOn401() = runTest {
        val ok = Harness { _, _, _ -> json("""{"login":"octo"}""") }
        ok.repo.restore()
        assertEquals("octo", ok.repo.account.value?.login)

        val revoked = Harness { _, _, _ -> json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }
        revoked.repo.restore()
        assertNull(revoked.repo.account.value)
        assertNull(revoked.store.read())
    }

    @Test
    fun unauthorizedDuringUseClearsAccount() = runTest {
        var revoked = false
        val h = Harness { _, path, _ ->
            if (path == "/user" && !revoked) json("""{"login":"octo"}""")
            else json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized)
        }
        h.repo.connect("tok")
        revoked = true
        assertFailsWith<GitHubException.Unauthorized> { h.repo.repo("o/r") }
        assertNull(h.repo.account.value)
        assertNull(h.store.read())
    }

    @Test
    fun ensureTaskBranchCreatesFromDefaultBranch() = runTest {
        val h = Harness { method, path, _ ->
            when ("$method $path") {
                "GET /repos/o/r" -> json("""{"full_name":"o/r","default_branch":"develop","private":true}""")
                "GET /repos/o/r/git/ref/heads/develop" -> json("""{"ref":"refs/heads/develop","object":{"sha":"base1"}}""")
                "POST /repos/o/r/git/refs" -> json("""{"ref":"refs/heads/task/t1-x","object":{"sha":"base1"}}""", HttpStatusCode.Created)
                else -> error("unexpected $method $path")
            }
        }
        val branch = h.repo.ensureTaskBranch("o/r", "task/t1-x")
        assertEquals(TaskBranch("o/r", "task/t1-x", "develop", "base1"), branch)
        val post = h.calls.last()
        assertEquals("refs/heads/task/t1-x", post.body!!["ref"]!!.jsonPrimitive.content)
        assertEquals("base1", post.body["sha"]!!.jsonPrimitive.content)
        assertEquals("Bearer tok", post.auth)
    }

    @Test
    fun ensureTaskBranchReturnsExistingBranch() = runTest {
        val h = Harness { method, path, _ ->
            when ("$method $path") {
                "GET /repos/o/r" -> json("""{"full_name":"o/r","default_branch":"main"}""")
                "GET /repos/o/r/git/ref/heads/main" -> json("""{"object":{"sha":"base1"}}""")
                "POST /repos/o/r/git/refs" -> json("""{"message":"Reference already exists"}""", HttpStatusCode.UnprocessableEntity)
                "GET /repos/o/r/git/ref/heads/task/t1-x" -> json("""{"object":{"sha":"work9"}}""")
                else -> error("unexpected $method $path")
            }
        }
        assertEquals(TaskBranch("o/r", "task/t1-x", "main", "work9"), h.repo.ensureTaskBranch("o/r", "task/t1-x"))
    }

    @Test
    fun treeIsSortedFoldersFirstAndSkipsSubmodules() = runTest {
        val h = Harness { method, path, _ ->
            when ("$method $path") {
                "GET /repos/o/r/git/ref/heads/main" -> json("""{"object":{"sha":"c1"}}""")
                "GET /repos/o/r/git/trees/c1" -> json(
                    """{"sha":"t1","truncated":false,"tree":[
                    {"path":"README.md","type":"blob","sha":"b1","size":10},
                    {"path":"src","type":"tree","sha":"t2"},
                    {"path":"src/z.js","type":"blob","sha":"b2","size":3},
                    {"path":"src/api","type":"tree","sha":"t3"},
                    {"path":"src/api/a.js","type":"blob","sha":"b3","size":4},
                    {"path":"vendor","type":"commit","sha":"s1"},
                    {"path":"a.txt","type":"blob","sha":"b4","size":1}]}""",
                )
                else -> error("unexpected $method $path")
            }
        }
        val tree = h.repo.tree("o/r", "main")
        assertEquals(listOf("src", "src/api", "src/api/a.js", "src/z.js", "a.txt", "README.md"), tree.map { it.path })
        assertEquals(RepoEntryType.Directory, tree[0].type)
        assertEquals(4L, tree[2].size)
        assertEquals("recursive=1", h.calls.last().query)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun fileDecodesBase64WithNewlines() = runTest {
        val text = "const é = 'ünïcode';\n".repeat(20)
        val encoded = Base64.encode(text.encodeToByteArray()).chunked(60).joinToString("\\n")
        val h = Harness { method, path, _ ->
            when ("$method $path") {
                "GET /repos/o/r/contents/src/my%20file.js" ->
                    json("""{"type":"file","path":"src/my file.js","sha":"f1","size":${text.encodeToByteArray().size},"encoding":"base64","content":"$encoded"}""")
                else -> error("unexpected $method $path")
            }
        }
        val file = h.repo.file("o/r", "task/t1-x", "src/my file.js")
        assertEquals(RepoFile("src/my file.js", text, "f1"), file)
        assertEquals("ref=task%2Ft1-x", h.calls.single().query)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun fileRejectsBinaryAndLargeFiles() = runTest {
        val binary = Base64.encode(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 1, 2))
        val h = Harness { _, path, _ ->
            if (path.endsWith("big.json")) json("""{"type":"file","path":"big.json","sha":"x","size":2000000,"encoding":"none","content":""}""")
            else json("""{"type":"file","path":"logo.png","sha":"y","size":7,"encoding":"base64","content":"$binary"}""")
        }
        val e1 = assertFailsWith<GitHubException.Conflict> { h.repo.file("o/r", "main", "logo.png") }
        assertEquals("This file is too large or binary to edit in the app", e1.message)
        assertFailsWith<GitHubException.Conflict> { h.repo.file("o/r", "main", "big.json") }
    }

    @Test
    fun commitUsesGitDataApiInOrder() = runTest {
        var blobs = 0
        val h = Harness { method, path, _ ->
            when ("$method $path") {
                "GET /repos/o/r/git/ref/heads/task/t1-x" -> json("""{"object":{"sha":"head1"}}""")
                "GET /repos/o/r/git/commits/head1" -> json("""{"sha":"head1","tree":{"sha":"tree0"}}""")
                "POST /repos/o/r/git/blobs" -> json("""{"sha":"blob${++blobs}"}""", HttpStatusCode.Created)
                "POST /repos/o/r/git/trees" -> json("""{"sha":"tree1"}""", HttpStatusCode.Created)
                "POST /repos/o/r/git/commits" -> json("""{"sha":"c2","html_url":"https://github.com/o/r/commit/c2"}""", HttpStatusCode.Created)
                "PATCH /repos/o/r/git/refs/heads/task/t1-x" -> json("""{"object":{"sha":"c2"}}""")
                else -> error("unexpected $method $path")
            }
        }
        val result = h.repo.commit(
            "o/r", "task/t1-x", "Fix date check",
            listOf(FileChange("src/a.js", "a()"), FileChange("src/b.js", "b()"), FileChange("old.txt", null)),
        )
        assertEquals(CommitResult("c2", "https://github.com/o/r/commit/c2", "Fix date check"), result)
        assertEquals(
            listOf(
                "GET /repos/o/r/git/ref/heads/task/t1-x",
                "GET /repos/o/r/git/commits/head1",
                "POST /repos/o/r/git/blobs",
                "POST /repos/o/r/git/blobs",
                "POST /repos/o/r/git/trees",
                "POST /repos/o/r/git/commits",
                "PATCH /repos/o/r/git/refs/heads/task/t1-x",
            ),
            h.calls.map { "${it.method} ${it.path}" },
        )
        assertEquals("a()", h.calls[2].body!!["content"]!!.jsonPrimitive.content)
        assertEquals("utf-8", h.calls[2].body!!["encoding"]!!.jsonPrimitive.content)

        val treeBody = h.calls[4].body!!
        assertEquals("tree0", treeBody["base_tree"]!!.jsonPrimitive.content)
        val entries = treeBody["tree"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("src/a.js", "src/b.js", "old.txt"), entries.map { it["path"]!!.jsonPrimitive.content })
        assertEquals("blob1", entries[0]["sha"]!!.jsonPrimitive.content)
        assertEquals("blob2", entries[1]["sha"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, entries[2]["sha"])
        assertTrue(entries.all { it["mode"]!!.jsonPrimitive.content == "100644" && it["type"]!!.jsonPrimitive.content == "blob" })

        val commitBody = h.calls[5].body!!
        assertEquals("Fix date check", commitBody["message"]!!.jsonPrimitive.content)
        assertEquals("tree1", commitBody["tree"]!!.jsonPrimitive.content)
        assertEquals(listOf("head1"), commitBody["parents"]!!.jsonArray.map { it.jsonPrimitive.content })

        val patch = h.calls[6].body!!
        assertEquals("c2", patch["sha"]!!.jsonPrimitive.content)
        assertEquals("false", patch["force"]!!.jsonPrimitive.content)
    }

    @Test
    fun openPullRequestReturnsExistingOnConflict() = runTest {
        val h = Harness { method, path, _ ->
            when ("$method $path") {
                "POST /repos/o/r/pulls" -> json(
                    """{"message":"Validation Failed","errors":[{"resource":"PullRequest","code":"custom","message":"A pull request already exists for o:task/t1-x."}]}""",
                    HttpStatusCode.UnprocessableEntity,
                )
                "GET /repos/o/r/pulls" -> json("""[{"number":7,"html_url":"https://github.com/o/r/pull/7","title":"Earlier title"}]""")
                else -> error("unexpected $method $path")
            }
        }
        val pr = h.repo.openPullRequest("o/r", "task/t1-x", "main", "Fix", "Body")
        assertEquals(PullRequest(7, "https://github.com/o/r/pull/7", "Earlier title"), pr)
        val post = h.calls[0].body!!
        assertEquals("task/t1-x", post["head"]!!.jsonPrimitive.content)
        assertEquals("main", post["base"]!!.jsonPrimitive.content)
        assertTrue(h.calls[1].query.contains("head=o%3Atask%2Ft1-x"), h.calls[1].query)
    }

    @Test
    fun mapsRateLimitForbiddenNotFoundAndConflict() = runTest {
        val h = Harness { _, path, _ ->
            when (path) {
                "/repos/o/limited" -> json(
                    """{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden,
                    "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1700000000",
                )
                "/repos/o/forbidden" -> json("""{"message":"Resource not accessible by personal access token"}""", HttpStatusCode.Forbidden)
                "/repos/o/missing" -> json("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
                else -> json("""{"message":"Is a conflict"}""", HttpStatusCode.Conflict)
            }
        }
        assertFailsWith<GitHubException.RateLimited> { h.repo.repo("o/limited") }
        val forbidden = assertFailsWith<GitHubException.Forbidden> { h.repo.repo("o/forbidden") }
        assertTrue(forbidden.message!!.contains("Resource not accessible"))
        assertFailsWith<GitHubException.NotFound> { h.repo.repo("o/missing") }
        assertEquals("Is a conflict", assertFailsWith<GitHubException.Conflict> { h.repo.repo("o/other") }.message)
    }

    @Test
    fun noTokenMeansUnauthorizedWithoutCallingGitHub() = runTest {
        val h = Harness(token = null) { _, _, _ -> error("should not be called") }
        assertFailsWith<GitHubException.Unauthorized> { h.repo.tree("o/r", "main") }
        assertTrue(h.calls.isEmpty())
    }

    @Test
    fun ioFailureMapsToNetwork() = runTest {
        val h = Harness { _, _, _ -> throw IllegalStateException("offline") }
        // POST: not retried, so no backoff delay in the test.
        assertFailsWith<GitHubException.Network> { h.repo.openPullRequest("o/r", "task/x", "main", "t", "b") }
    }
}
