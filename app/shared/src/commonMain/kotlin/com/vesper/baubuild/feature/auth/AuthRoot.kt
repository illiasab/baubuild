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

/** The ViewModels of the signed-out flow, created once per AuthRoot. */
private class AuthViewModels(
    val nav: AuthViewModel,
    val signUp: SignUpViewModel,
    val login: LoginViewModel,
)

/**
 * Entry point for this feature. Hosted by the Compose tab shell and by the native iOS TabView.
 * In-auth navigation: Welcome → Log In / Sign Up, and Forgot Password (a "ask support" card — no email is sent).
 * Log In ⇄ Sign Up links pop back to an existing entry instead of stacking; system back (Android back, iOS
 * edge-swipe, desktop Esc) pops. Sign Up and Log In store the session and the shell leaves this root.
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
        AuthViewModel(graph.session, graph.policy, debugInitialAuthScreen())
    }
    val vms = AuthViewModels(
        nav = vm,
        signUp = viewModel { SignUpViewModel(AppGraph.current.authRepository, AppGraph.current.policy, vm) },
        login = viewModel { LoginViewModel(AppGraph.current.authRepository, AppGraph.current.policy, vm) },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    val blocked = state.status.takeIf { state.isBlocked }
    val content: AuthContent = if (blocked != null) AuthContent.Blocked(blocked) else AuthContent.Flow(state.screen, state.backStack.size)

    BackHandler(enabled = blocked == null && state.canGoBack) { back(vms, state) }

    if (usesPortalLayout) {
        val formScroll = rememberScrollState()
        val contentKey = if (content is AuthContent.Flow) content.screen.name else "blocked"
        LaunchedEffect(contentKey) { formScroll.scrollTo(0) }
        DesktopAuthFrame(formScroll, modifier) {
            AuthContentHost(content, desktop = true, vms, state, Modifier)
        }
    } else {
        AuthContentHost(content, desktop = false, vms, state, modifier.fillMaxSize())
    }
}

/** System / header back pops the auth stack. */
@Suppress("UNUSED_PARAMETER")
private fun back(vms: AuthViewModels, state: AuthUiState) {
    vms.nav.back()
}

/** Animated switch between the auth screens; [desktop] picks the desktop card or the full-screen mobile layout. */
@Composable
private fun AuthContentHost(
    content: AuthContent,
    desktop: Boolean,
    vms: AuthViewModels,
    state: AuthUiState,
    modifier: Modifier,
) {
    val vm = vms.nav
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
        val onBack = { back(vms, state) }
        val onLogIn = { vm.navigateTo(AuthScreen.Login) }
        val onSignUp = { vm.navigateTo(AuthScreen.SignUp) }
        val onForgot = { vm.navigateTo(AuthScreen.ForgotPassword) }
        when (target) {
            is AuthContent.Blocked ->
                if (desktop) DesktopNotAllowed(target.status, vm::useAnotherAccount, vm::dismissNotAllowed)
                else NotAllowedScreen(status = target.status, onUseAnotherAccount = vm::useAnotherAccount, onDismiss = vm::dismissNotAllowed)
            is AuthContent.Flow -> when (target.screen) {
                AuthScreen.Welcome ->
                    if (desktop) DesktopWelcome(onLogIn, onSignUp, onForgot)
                    else WelcomeScreen(onLogIn = onLogIn, onSignUp = onSignUp, onForgotPassword = onForgot)
                AuthScreen.Login -> {
                    val login by vms.login.state.collectAsStateWithLifecycle()
                    val l = vms.login
                    if (desktop) {
                        DesktopLogin(login, l::onEmailChange, l::onPasswordChange, l::onTogglePasswordVisible, l::submit, onForgot, onSignUp, onBack)
                    } else {
                        LoginScreen(
                            state = login,
                            onEmailChange = l::onEmailChange,
                            onPasswordChange = l::onPasswordChange,
                            onTogglePasswordVisible = l::onTogglePasswordVisible,
                            onSubmit = l::submit,
                            onForgotPassword = onForgot,
                            onSignUp = onSignUp,
                            onBack = onBack,
                        )
                    }
                }
                AuthScreen.SignUp -> {
                    val signUp by vms.signUp.state.collectAsStateWithLifecycle()
                    val s = vms.signUp
                    if (desktop) {
                        DesktopSignUp(
                            signUp, s::onNameChange, s::onEmailChange, s::onPasswordChange, s::onConfirmPasswordChange,
                            s::onTogglePasswordVisible, s::onTermsAcceptedChange, s::submit, onLogIn, onBack,
                        )
                    } else {
                        SignUpScreen(
                            state = signUp,
                            onNameChange = s::onNameChange,
                            onEmailChange = s::onEmailChange,
                            onPasswordChange = s::onPasswordChange,
                            onConfirmPasswordChange = s::onConfirmPasswordChange,
                            onTogglePasswordVisible = s::onTogglePasswordVisible,
                            onTermsAcceptedChange = s::onTermsAcceptedChange,
                            onSubmit = s::submit,
                            onLogIn = onLogIn,
                            onBack = onBack,
                        )
                    }
                }
                AuthScreen.ForgotPassword ->
                    if (desktop) DesktopForgotPassword(onLogIn, onBack)
                    else ForgotPasswordScreen(onLogIn = onLogIn, onBack = onBack)
            }
        }
    }
}
