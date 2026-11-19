package com.vesper.baubuild.core.auth

import kotlinx.coroutines.delay

/**
 * Test stand-in for [MicrosoftOAuthSignIn]. Returns a code `fake-code:<email>` that
 * [com.vesper.baubuild.data.fake.FakeAuthRepository] understands, so tests can drive the student, staff and
 * blocked flows without Entra ID.
 */
class FakeMicrosoftSignIn(
    val demoEmails: List<String> = DEFAULT_DEMO_EMAILS,
    private val latencyMillis: Long = 400,
) : MicrosoftSignIn {
    var selectedEmail: String = demoEmails.first()

    /** The last hint received, so tests and the demo can see what the real flow would pre-fill. */
    var lastLoginHint: String? = null
        private set

    override suspend fun signIn(loginHint: String?): MicrosoftAuthorization {
        lastLoginHint = loginHint
        delay(latencyMillis)
        return MicrosoftAuthorization(code = FAKE_CODE_PREFIX + selectedEmail, codeVerifier = "fake-verifier", redirectUri = "fake://auth")
    }

    companion object {
        const val FAKE_CODE_PREFIX = "fake-code:"
        val DEFAULT_DEMO_EMAILS = listOf("sam.carter@stu.bau.edu", "dr.aydin@bau.edu", "alex@gmail.com")
    }
}
