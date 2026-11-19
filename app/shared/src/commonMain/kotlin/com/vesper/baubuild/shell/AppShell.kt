package com.vesper.baubuild.shell

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.auth.AuthRoot
import com.vesper.baubuild.feature.mywork.MyWorkTabRoot
import com.vesper.baubuild.feature.profile.ProfileTabRoot
import com.vesper.baubuild.feature.projects.ProjectsTabRoot
import com.vesper.baubuild.feature.review.ReviewTabRoot
import com.vesper.baubuild.feature.search.SearchRoot

/** Tabs from the design. [Search] is the separate glass button to the right of the tab bar. */
enum class AppTab { Projects, MyWork, Review, Profile, Search }

private enum class ShellPhase { Restoring, SignedOut, SignedIn }

/** Window width from which the side rail replaces the floating bottom bar (Material "expanded"). */
private val WideLayoutMinWidth = 840.dp

/**
 * Compose shell for Android and desktop (iOS uses the native SwiftUI shell in iosApp/).
 * Restoring → splash, SignedOut → [AuthRoot], SignedIn → tab shell.
 */
@Composable
fun AppShell() {
    val graph = AppGraph.current
    val session by graph.session.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (graph.session.state.value == SessionState.Restoring) {
            graph.session.restore { graph.authRepository.me(it) }
        }
    }
    val phase = when (session) {
        SessionState.Restoring -> ShellPhase.Restoring
        SessionState.SignedOut -> ShellPhase.SignedOut
        is SessionState.SignedIn -> ShellPhase.SignedIn
    }
    Crossfade(targetState = phase, animationSpec = tween(220), label = "session") { current ->
        when (current) {
            ShellPhase.Restoring -> SplashScreen()
            ShellPhase.SignedOut -> Box(Modifier.fillMaxSize().background(BauColors.Paper)) { AuthRoot() }
            ShellPhase.SignedIn -> SignedInShell()
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(BauColors.Paper), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark()
            Spacer(Modifier.height(16.dp))
            Text(
                "BAU BUILD",
                color = BauColors.Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

@Composable
private fun SignedInShell() {
    var selected by rememberSaveable { mutableStateOf(AppTab.Projects) }
    val tabStates = rememberSaveableStateHolder()
    val select: (AppTab) -> Unit = { selected = it }

    val tabContent: @Composable () -> Unit = {
        // Each tab keeps its saveable state (scroll positions, in-tab navigation) while hidden.
        tabStates.SaveableStateProvider(selected.name) {
            when (selected) {
                AppTab.Projects -> ProjectsTabRoot()
                AppTab.MyWork -> MyWorkTabRoot()
                AppTab.Review -> ReviewTabRoot()
                AppTab.Profile -> ProfileTabRoot()
                AppTab.Search -> SearchRoot()
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(BauColors.Paper)) {
        if (maxWidth >= WideLayoutMinWidth) {
            Row(Modifier.fillMaxSize()) {
                GlassSideRail(
                    selected = selected,
                    onSelect = select,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                        .padding(16.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
                ) { tabContent() }
            }
        } else {
            val systemBottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
            // Reserve the floating bar's footprint and consume it, so roots that pad with
            // WindowInsets.safeDrawing don't add the system bottom inset a second time.
            val contentPadding = PaddingValues(bottom = systemBottom + BarBottomGap + GlassBarHeight + 8.dp)
            Box(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)) { tabContent() }

            val sideGutter = 20.dp
            val pillWidth = min(maxWidth - sideGutter * 2 - GlassBarHeight - BarItemGap, 380.dp)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = systemBottom + BarBottomGap),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassTabBar(selected = selected, onSelect = select, modifier = Modifier.width(pillWidth))
                Spacer(Modifier.width(BarItemGap))
                GlassSearchButton(selected = selected == AppTab.Search, onClick = { select(AppTab.Search) })
            }
        }
    }
}
