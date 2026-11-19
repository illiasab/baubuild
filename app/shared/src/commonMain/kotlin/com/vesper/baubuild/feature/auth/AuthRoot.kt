package com.vesper.baubuild.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.auth.desktop.DesktopAuthFrame
import com.vesper.baubuild.feature.auth.desktop.DesktopForgotPassword
import com.vesper.baubuild.feature.auth.desktop.DesktopLogin
import com.vesper.baubuild.feature.auth.desktop.DesktopNotAllowed
import com.vesper.baubuild.feature.auth.desktop.DesktopSignUp
import com.vesper.baubuild.feature.auth.desktop.DesktopWelcome
import com.vesper.baubuild.shell.usesPortalLayout

/** What AuthRoot is showing: one of the signed-out screens, or the "not allowed" explanation on top of them. */
private sealed interface AuthContent {
    /** [depth] = back stack size, so pushes slide forward and pops slide back. */
    data class Flow(val screen: AuthScreen, val depth: Int) : AuthContent
    data class Blocked(val status: AuthStatus) : AuthContent
}

/**
 * Entry point for this feature. Hosted by the Compose tab shell and by the native iOS TabView.
 * In-auth navigation: Welcome → Log In / Sign Up / Forgot Password (Log In ⇄ Sign Up links pop back to an
 * existing entry instead of stacking), with system back (Android back, iOS edge-swipe, desktop Esc) popping.
 *
 * Desktop ([usesPortalLayout]) renders the same flow in the website layout ([DesktopAuthFrame]: dark brand panel +
 * form card); only the card changes between screens. Mobile renders the full-screen phone layouts.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // ui-backhandler BackHandler; move to NavigationBackHandler with the other tabs.
@Composable
fun AuthRoot(modifier: Modifier = Modifier) {
    val vm = viewModel {
        val graph = AppGraph.current
        AuthViewModel(graph.microsoftSignIn, graph.authRepository, graph.session, graph.policy)
    }
    val signUpVm = viewModel { SignUpViewModel(AppGraph.current.policy) }
    val loginVm = viewModel { LoginViewModel(AppGraph.current.policy) }
    val forgotVm = viewModel { ForgotPasswordViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()

    // Each AnimatedContent slot keeps its own status, so the outgoing screen doesn't flash during the fade.
    val blocked = state.status.takeIf { it is AuthStatus.BlockedDomain || it is AuthStatus.RequiresPayment }
    val content: AuthContent = if (blocked != null) AuthContent.Blocked(blocked) else AuthContent.Flow(state.screen, state.backStack.size)

    BackHandler(enabled = blocked == null && state.canGoBack) { vm.back() }

    if (usesPortalLayout) {
        val formScroll = rememberScrollState()
        val contentKey = if (content is AuthContent.Flow) content.screen.name else "blocked"
        LaunchedEffect(contentKey) { formScroll.scrollTo(0) }
        DesktopAuthFrame(formScroll, modifier) {
            AuthContentHost(content, desktop = true, vm, signUpVm, loginVm, forgotVm, state, Modifier)
        }
    } else {
        AuthContentHost(content, desktop = false, vm, signUpVm, loginVm, forgotVm, state, modifier.fillMaxSize())
    }
}

/** Animated switch between the auth screens; [desktop] picks the desktop card or the full-screen mobile layout. */
@Composable
private fun AuthContentHost(
    content: AuthContent,
    desktop: Boolean,
    vm: AuthViewModel,
    signUpVm: SignUpViewModel,
    loginVm: LoginViewModel,
    forgotVm: ForgotPasswordViewModel,
    state: AuthUiState,
    modifier: Modifier,
) {
    val uriHandler = LocalUriHandler.current
    // Desktop: the card only shifts a little (the brand panel stays put); mobile slides a quarter screen.
    val slideDivisor = if (desktop) 16 else 4
    AnimatedContent(
        targetState = content,
        modifier = modifier,
        contentKey = { if (it is AuthContent.Flow) it.screen.name else "blocked" },
        transitionSpec = {
            val from = initialState
            val to = targetState
            if (from is AuthContent.Flow && to is AuthContent.Flow) {
                val forward = to.depth > from.depth
                val enter = slideInHorizontally(tween(260)) { w -> if (forward) w / slideDivisor else -w / slideDivisor } + fadeIn(tween(200))
                val exit = slideOutHorizontally(tween(260)) { w -> if (forward) -w / slideDivisor else w / slideDivisor } + fadeOut(tween(160))
                enter togetherWith exit
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        label = "auth-nav",
    ) { target ->
        when (target) {
            is AuthContent.Blocked ->
                if (desktop) DesktopNotAllowed(target.status, vm::useAnotherAccount, vm::signOut)
                else NotAllowedScreen(status = target.status, onUseAnotherAccount = vm::useAnotherAccount, onSignOut = vm::signOut)
            is AuthContent.Flow -> when (target.screen) {
                AuthScreen.Welcome -> {
                    val onLogIn = { vm.navigateTo(AuthScreen.Login) }
                    val onSignUp = { vm.navigateTo(AuthScreen.SignUp) }
                    val onForgot = { vm.navigateTo(AuthScreen.ForgotPassword) }
                    if (desktop) {
                        DesktopWelcome(state, onLogIn, onSignUp, onForgot, vm::cancelSignIn)
                    } else {
                        WelcomeScreen(
                            state = state,
                            onLogIn = onLogIn,
                            onSignUp = onSignUp,
                            onForgotPassword = onForgot,
                            onCancel = vm::cancelSignIn,
                        )
                    }
                }
                AuthScreen.Login -> {
                    val login by loginVm.state.collectAsStateWithLifecycle()
                    val onEmailChange: (String) -> Unit = { vm.dismissError(); loginVm.onEmailChange(it) }
                    // Microsoft only: the typed address pre-fills Microsoft's account picker.
                    val onContinue = { vm.signIn(login.loginHint) }
                    val onForgot = { vm.navigateTo(AuthScreen.ForgotPassword) }
                    val onSignUp = { vm.navigateTo(AuthScreen.SignUp) }
                    val onBack = { vm.back(); Unit }
                    if (desktop) {
                        DesktopLogin(state, login, onEmailChange, onContinue, vm::cancelSignIn, onForgot, onSignUp, onBack)
                    } else {
                        LoginScreen(
                            authState = state,
                            state = login,
                            onEmailChange = onEmailChange,
                            onContinue = onContinue,
                            onCancelSignIn = vm::cancelSignIn,
                            onForgotPassword = onForgot,
                            onSignUp = onSignUp,
                            onBack = onBack,
                        )
                    }
                }
                AuthScreen.SignUp -> {
                    val signUp by signUpVm.state.collectAsStateWithLifecycle()
                    val onContinue = { vm.signIn(signUp.loginHint) }
                    val onLogIn = { vm.navigateTo(AuthScreen.Login) }
                    val onBack = { vm.back(); Unit }
                    if (desktop) {
                        DesktopSignUp(
                            state, signUp, signUpVm::onEmailChange, signUpVm::onTermsAcceptedChange,
                            onContinue, vm::cancelSignIn, onLogIn, onBack,
                        )
                    } else {
                        SignUpScreen(
                            authState = state,
                            state = signUp,
                            onEmailChange = signUpVm::onEmailChange,
                            onTermsAcceptedChange = signUpVm::onTermsAcceptedChange,
                            onContinue = onContinue,
                            onCancelSignIn = vm::cancelSignIn,
                            onLogIn = onLogIn,
                            onBack = onBack,
                        )
                    }
                }
                AuthScreen.ForgotPassword -> {
                    val forgot by forgotVm.state.collectAsStateWithLifecycle()
                    val onReset = { forgotVm.openLink(MICROSOFT_PASSWORD_RESET_URL, uriHandler::openUri) }
                    val onContactIt = { forgotVm.openLink(BAU_IT_SUPPORT_MAILTO, uriHandler::openUri) }
                    val onBackToSignIn = { vm.navigateTo(AuthScreen.Login) }
                    val onBack = { vm.back(); Unit }
                    if (desktop) {
                        DesktopForgotPassword(forgot, onReset, onContactIt, onBackToSignIn, onBack)
                    } else {
                        ForgotPasswordScreen(
                            state = forgot,
                            onResetWithMicrosoft = onReset,
                            onContactIt = onContactIt,
                            onBackToSignIn = onBackToSignIn,
                            onBack = onBack,
                        )
                    }
                }
            }
        }
    }
}
