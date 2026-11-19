package com.vesper.baubuild.core.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

actual fun platformAuthBrowser(): AuthBrowser = AndroidAuthBrowser

/**
 * Opens the authorize URL in a Custom Tab (falls back to any browser). Microsoft redirects to
 * `com.vesper.baubuild://auth?...`, which [AuthRedirectActivity] receives and hands to [onRedirect].
 * If the user closes the tab and returns to the app without a redirect, the flow ends as cancelled.
 *
 * Limitation: if Android kills the process while the browser is open, the pending sign-in is lost and the
 * user simply taps Sign Up again.
 */
object AndroidAuthBrowser : AuthBrowser {
    private val main = Handler(Looper.getMainLooper())
    private var pending: CompletableDeferred<String>? = null
    private var leftApp = false
    private val cancelIfReturnedWithoutRedirect = Runnable {
        pending?.completeExceptionally(MicrosoftSignInCancelled())
    }

    init {
        AuthPlatform.listeners += { activity, resumed ->
            if (pending != null && activity !is AuthRedirectActivity) {
                if (!resumed) leftApp = true
                else if (leftApp) main.postDelayed(cancelIfReturnedWithoutRedirect, 600) // give the redirect time to land
            }
        }
    }

    override suspend fun authorize(
        config: MicrosoftAuthConfig,
        buildAuthorizeUrl: (redirectUri: String) -> String,
    ): AuthCallback {
        val redirectUri = config.androidRedirectUri
        val url = Uri.parse(buildAuthorizeUrl(redirectUri))
        val deferred = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            pending?.completeExceptionally(MicrosoftSignInCancelled())
            main.removeCallbacks(cancelIfReturnedWithoutRedirect)
            pending = deferred
            leftApp = false
            launch(url)
        }
        try {
            return AuthCallback(redirectUri, deferred.await())
        } finally {
            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                main.removeCallbacks(cancelIfReturnedWithoutRedirect)
                if (pending === deferred) pending = null
            }
        }
    }

    private fun launch(url: Uri) {
        val activity = AuthPlatform.resumedActivity
        val context = activity ?: AuthPlatform.context
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        if (activity == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            tab.launchUrl(context, url)
        } catch (e: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    internal fun onRedirect(uri: Uri) {
        main.removeCallbacks(cancelIfReturnedWithoutRedirect)
        pending?.complete(uri.toString())
    }
}

/**
 * Receives the OAuth redirect (declared in app/androidApp's manifest with the redirect scheme), forwards it,
 * then brings the app's launcher activity back to the front, closing the Custom Tab above it.
 */
class AuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(AndroidAuthBrowser::onRedirect)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
        finish()
    }
}
