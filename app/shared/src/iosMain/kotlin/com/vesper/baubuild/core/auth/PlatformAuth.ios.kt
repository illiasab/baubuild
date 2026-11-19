@file:OptIn(ExperimentalForeignApi::class)

package com.vesper.baubuild.core.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorDomain
import platform.Foundation.NSURL
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return bytes
}

actual fun platformAuthBrowser(): AuthBrowser = IosAuthBrowser()

/**
 * ASWebAuthenticationSession: the system sign-in sheet that shares Safari's cookies (so an existing BAU
 * Microsoft session is reused) and returns the redirect to us. The callback scheme does not need to be
 * registered in Info.plist.
 */
class IosAuthBrowser : AuthBrowser {
    // ASWebAuthenticationSession holds its provider weakly and must itself stay alive while presented.
    private val anchorProvider = KeyWindowAnchorProvider()
    private var session: ASWebAuthenticationSession? = null

    override suspend fun authorize(
        config: MicrosoftAuthConfig,
        buildAuthorizeUrl: (redirectUri: String) -> String,
    ): AuthCallback = withContext(Dispatchers.Main) {
        val redirectUri = config.iosRedirectUri
        val url = NSURL.URLWithString(buildAuthorizeUrl(redirectUri))
            ?: throw MicrosoftSignInException("invalid_request", "Couldn't build the Microsoft sign-in URL.")
        val callbackUrl = suspendCancellableCoroutine { cont ->
            val authSession = ASWebAuthenticationSession(
                uRL = url,
                callbackURLScheme = redirectUri.substringBefore("://"),
            ) { callback, error ->
                session = null
                when {
                    callback != null -> cont.resume(callback.absoluteString ?: "")
                    error != null && error.domain == ASWebAuthenticationSessionErrorDomain &&
                        error.code == ASWebAuthenticationSessionErrorCodeCanceledLogin ->
                        cont.resumeWithException(MicrosoftSignInCancelled())
                    else -> cont.resumeWithException(
                        MicrosoftSignInException("browser_error", error?.localizedDescription ?: "Sign-in sheet failed."),
                    )
                }
            }
            authSession.presentationContextProvider = anchorProvider
            authSession.prefersEphemeralWebBrowserSession = false
            session = authSession
            cont.invokeOnCancellation { authSession.cancel() }
            if (!authSession.start()) {
                session = null
                cont.resumeWithException(MicrosoftSignInException("browser_error", "Couldn't open the sign-in sheet."))
            }
        }
        AuthCallback(redirectUri, callbackUrl)
    }
}

private class KeyWindowAnchorProvider : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor {
        val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
        val windows = (scenes.filter { it.activationState == UISceneActivationStateForegroundActive } + scenes)
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        return windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() ?: UIWindow()
    }
}
