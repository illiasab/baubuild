package com.vesper.baubuild.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.di.AppGraph

/** Entry point for this feature. Hosted by the Compose tab shell and by the native iOS TabView. */
@Composable
fun AuthRoot(modifier: Modifier = Modifier) {
    val vm = viewModel {
        val graph = AppGraph.current
        AuthViewModel(graph.microsoftSignIn, graph.authRepository, graph.session, graph.policy)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Each AnimatedContent slot keeps its own status, so the outgoing screen doesn't flash during the fade.
    val notAllowed = state.status.takeIf { it is AuthStatus.BlockedDomain || it is AuthStatus.RequiresPayment }
    AnimatedContent(
        targetState = notAllowed,
        modifier = modifier.fillMaxSize(),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it != null },
    ) { blocked ->
        if (blocked == null) {
            WelcomeScreen(
                state = state,
                onSignIn = vm::signIn,
                onCancel = vm::cancelSignIn,
                onSelectDemoAccount = vm::selectDemoAccount,
            )
        } else {
            NotAllowedScreen(
                status = blocked,
                onUseAnotherAccount = vm::useAnotherAccount,
                onSignOut = vm::signOut,
            )
        }
    }
}
