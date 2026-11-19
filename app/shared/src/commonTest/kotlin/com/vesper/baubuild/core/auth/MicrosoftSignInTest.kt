package com.vesper.baubuild.core.auth

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MicrosoftSignInTest {

    @Test
    fun sha256MatchesKnownVectors() {
        fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.digest(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Sha256.digest("abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun pkceChallengeIsBase64UrlSha256OfVerifier() {
        // Expected value from Python: base64.urlsafe_b64encode(hashlib.sha256(verifier).digest()).rstrip("=")
        val pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mJ92K9qeiI6g1V4bCZm5hTQ9w1bH5Q")
        assertEquals("z3QwwmHRB-pfzu6jnR8ANWx-Cq6jFkyd1mcCE2tMlhs", pkce.challenge)
    }

    @Test
    fun generatedVerifierIsValidLength() {
        val verifier = Pkce.generate().verifier
        assertTrue(verifier.length in 43..128, "verifier length ${verifier.length}")
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    private val config = MicrosoftAuthConfig(clientId = "client-123", tenant = "tenant-abc")

    @Test
    fun buildsAuthorizeUrlAndReturnsCodeWithVerifier() = runTest {
        var openedUrl = ""
        val browser = object : AuthBrowser {
            override suspend fun authorize(config: MicrosoftAuthConfig, buildAuthorizeUrl: (String) -> String): AuthCallback {
                openedUrl = buildAuthorizeUrl("http://127.0.0.1:5555/auth/callback")
                return AuthCallback("http://127.0.0.1:5555/auth/callback", "http://127.0.0.1:5555/auth/callback?code=the-code&state=s1")
            }
        }
        val signIn = MicrosoftOAuthSignIn(config, browser, pkceFactory = { Pkce.fromVerifier("v".repeat(43)) }, stateFactory = { "s1" })

        val result = signIn.signIn()

        assertEquals(MicrosoftAuthorization("the-code", "v".repeat(43), "http://127.0.0.1:5555/auth/callback"), result)
        val url = Url(openedUrl)
        assertEquals("login.microsoftonline.com", url.host)
        assertEquals("/tenant-abc/oauth2/v2.0/authorize", url.encodedPath)
        assertEquals("client-123", url.parameters["client_id"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals(Pkce.fromVerifier("v".repeat(43)).challenge, url.parameters["code_challenge"])
        assertEquals("select_account", url.parameters["prompt"])
        assertEquals("bau.edu", url.parameters["domain_hint"])
        assertEquals("openid profile email", url.parameters["scope"])
        assertEquals("http://127.0.0.1:5555/auth/callback", url.parameters["redirect_uri"])
    }

    @Test
    fun rejectsStateMismatch() {
        val e = assertFailsWith<MicrosoftSignInException> {
            MicrosoftOAuthSignIn.parseCallback("com.vesper.baubuild://auth?code=c&state=evil", expectedState = "s1")
        }
        assertEquals("state_mismatch", e.error)
    }

    @Test
    fun surfacesOAuthErrorsAndCancellation() {
        val e = assertFailsWith<MicrosoftSignInException> {
            MicrosoftOAuthSignIn.parseCallback("com.vesper.baubuild://auth?error=invalid_request&error_description=Bad+thing&state=s1", "s1")
        }
        assertEquals("invalid_request", e.error)
        assertEquals("Bad thing", e.description)
        assertFailsWith<MicrosoftSignInCancelled> {
            MicrosoftOAuthSignIn.parseCallback("com.vesper.baubuild://auth?error=access_denied&error_subcode=cancel&state=s1", "s1")
        }
    }

    @Test
    fun parsesCustomSchemeCallback() {
        assertEquals("abc", MicrosoftOAuthSignIn.parseCallback("com.vesper.baubuild://auth?code=abc&state=s1&session_state=x", "s1"))
    }
}
