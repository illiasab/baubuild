package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.core.auth.MicrosoftAuthConfig
import com.vesper.baubuild.core.auth.MicrosoftOAuthSignIn
import com.vesper.baubuild.core.auth.Pkce
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgotPasswordViewModelTest {

    @Test
    fun opensMicrosoftResetPage() {
        val vm = ForgotPasswordViewModel()
        val opened = mutableListOf<String>()
        vm.openLink(MICROSOFT_PASSWORD_RESET_URL) { opened += it }
        assertEquals(listOf("https://passwordreset.microsoftonline.com/"), opened)
        assertNull(vm.state.value.unopenedLink)
    }

    @Test
    fun contactItIsAMailtoForTheSupportAddress() {
        assertTrue(BAU_IT_SUPPORT_MAILTO.startsWith("mailto:$BAU_IT_SUPPORT_EMAIL"))
    }

    @Test
    fun failureToOpenIsShownInlineAndClearsOnSuccess() {
        val vm = ForgotPasswordViewModel()
        vm.openLink(BAU_IT_SUPPORT_MAILTO) { throw IllegalStateException("no mail app") }
        assertEquals(BAU_IT_SUPPORT_MAILTO, vm.state.value.unopenedLink)

        vm.openLink(MICROSOFT_PASSWORD_RESET_URL) { }
        assertNull(vm.state.value.unopenedLink)

        vm.openLink(MICROSOFT_PASSWORD_RESET_URL) { throw IllegalStateException() }
        vm.dismissLinkError()
        assertNull(vm.state.value.unopenedLink)
    }
}

/** Lives here because tests under core/auth belong to another workstream; covers the Sign Up `login_hint`. */
class MicrosoftLoginHintTest {
    // Only builds URLs; a stub browser keeps platform (Android/iOS) browser classes out of host tests.
    private val signIn = MicrosoftOAuthSignIn(
        MicrosoftAuthConfig(clientId = "c", tenant = "t"),
        browser = object : com.vesper.baubuild.core.auth.AuthBrowser {
            override suspend fun authorize(
                config: MicrosoftAuthConfig,
                buildAuthorizeUrl: (redirectUri: String) -> String,
            ) = error("not used")
        },
    )
    private val pkce = Pkce.fromVerifier("v".repeat(43))

    @Test
    fun addsLoginHintWhenGiven() {
        val url = Url(signIn.authorizeUrl("app://auth", pkce, "s", loginHint = " sam@stu.bau.edu "))
        assertEquals("sam@stu.bau.edu", url.parameters["login_hint"])
    }

    @Test
    fun omitsBlankLoginHint() {
        assertNull(Url(signIn.authorizeUrl("app://auth", pkce, "s")).parameters["login_hint"])
        assertNull(Url(signIn.authorizeUrl("app://auth", pkce, "s", loginHint = "  ")).parameters["login_hint"])
    }
}
