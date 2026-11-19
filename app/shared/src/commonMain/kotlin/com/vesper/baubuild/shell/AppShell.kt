package com.vesper.baubuild.shell

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.exclude
import androidx.compose.runtime.CompositionLocalProvider
import com.vesper.baubuild.designsystem.LocalTabBarInset
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import baubuild.app.shared.generated.resources.Res
import baubuild.app.shared.generated.resources.app_logo
import org.jetbrains.compose.resources.painterResource
import com.vesper.baubuild.core.session.SessionState
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.auth.AuthRoot
import com.vesper.baubuild.feature.mywork.MyWorkTabRoot
import com.vesper.baubuild.feature.profile.ProfileTabRoot
import com.vesper.baubuild.feature.projects.ProjectsTabRoot
import com.vesper.baubuild.feature.review.ReviewTabRoot
import com.vesper.baubuild.feature.search.SearchRoot
import com.vesper.baubuild.feature.staff.isStaff
import com.vesper.baubuild.feature.staff.tour.StaffTour
import com.vesper.baubuild.shell.portal.PortalShell
import com.vesper.baubuild.shell.portal.StaffPortalShell
import com.vesper.baubuild.shell.staff.StaffShell

/** Tabs from the design. [Search] is the separate glass button to the right of the tab bar. */
enum class AppTab { Projects, MyWork, Review, Profile, Search }

private enum class ShellPhase { Restoring, SignedOut, SignedIn }

/**
 * Window width from which the side rail replaces the floating bottom bar (Material "expanded"). Only reached on
 * Android tablets / large windows now: desktop renders the Web portal shell ([PortalShell]) instead.
 */
private val WideLayoutMinWidth = 840.dp

/**
 * Compose shell for Android and desktop (iOS uses the native SwiftUI shell in iosApp/).
 * Restoring → splash, SignedOut → [AuthRoot], SignedIn → [PortalShell] on desktop ([usesPortalLayout]; [StaffPortalShell] for staff),
 * otherwise the staff shell ([StaffShell]) for @bau.edu staff ([isStaff]) or the student tab shell.
 */
@Composable
fun AppShell() {
    val graph = AppGraph.current
    val session by graph.session.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (graph.session.state.value == SessionState.Restoring) {
            graph.restoreSession()
        }
    }
    val phase = when (session) {
        SessionState.Restoring -> ShellPhase.Restoring
        SessionState.SignedOut -> ShellPhase.SignedOut
        is SessionState.SignedIn -> ShellPhase.SignedIn
    }
    LaunchedEffect(phase) {
        if (phase == ShellPhase.SignedOut) StaffTour.reset()
    }
    Crossfade(targetState = phase, animationSpec = tween(220), label = "session") { current ->
        when (current) {
            ShellPhase.Restoring -> SplashScreen()
            ShellPhase.SignedOut -> Box(Modifier.fillMaxSize().background(BauColors.Paper)) { AuthRoot() }
            ShellPhase.SignedIn -> {
                val user = (session as? SessionState.SignedIn)?.user
                when {
                    usesPortalLayout && user != null && user.isStaff -> StaffPortalShell(user)
                    usesPortalLayout -> PortalShell()
                    user != null && user.isStaff -> StaffShell(user)
                    else -> SignedInShell()
                }
            }
        }
    }
}

/** Same as the iOS launch/splash: the eagle logo centred on the logo's navy. */
@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(SplashBackground), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(Res.drawable.app_logo),
            contentDescription = "BAU BUILD, loading",
            modifier = Modifier.size(280.dp),
        )
    }
}

/** Navy sampled from docs/eagle.png so the logo's own background blends in. */
private val SplashBackground = Color(0xFF090D1F)

@Suppress("DEPRECATION") // ui-backhandler BackHandler; move to NavigationBackHandler with the other tabs.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SignedInShell() {
    val nav = rememberSaveable(saver = ShellNavigation.Saver) { ShellNavigation() }
    val selected = nav.selected
    val tabStates = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    // Leaving search keeps SearchQuery.text (Apple Music behaviour: the last query is there when you come back).
    val exitSearch: () -> Unit = {
        focusManager.clearFocus()
        nav.exitSearch()
    }

    // Composed before the tab content so in-tab handlers (detail screens, sheets) get back first.
    BackHandler(enabled = nav.isSearching, onBack = exitSearch)

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
                    onSelect = { tab ->
                        if (nav.isSearching && tab != AppTab.Search) focusManager.clearFocus()
                        nav.select(tab)
                    },
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                        .padding(16.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
                ) {
                    if (nav.isSearching) {
                        // No bottom bar to morph on wide windows: the field sits above the results.
                        WideSearchBar(
                            Modifier
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                                .padding(start = 24.dp, end = 24.dp, top = 16.dp),
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(
                                if (nav.isSearching) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                                else Modifier,
                            ),
                    ) { tabContent() }
                }
            }
        } else {
            // The bar rests on the system navigation inset and stays behind the soft keyboard, except while the
            // search field (which lives in the bar) is active: then it rides on top of the keyboard.
            val barBase = if (nav.isSearching) WindowInsets.safeDrawing.union(WindowInsets.ime) else WindowInsets.safeDrawing.exclude(WindowInsets.ime)
            val systemBottom = barBase.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
            // Content extends under the glass bar (like the iOS TabView). The bar's footprint reaches screens once,
            // through LocalTabBarInset, and designsystem/BottomBarSpacing.kt turns it into their bottom padding.
            CompositionLocalProvider(LocalTabBarInset provides systemBottom + BarBottomGap + GlassBarHeight) {
                Box(Modifier.fillMaxSize()) { tabContent() }
            }

            val sideGutter = 20.dp
            val pillWidth = min(maxWidth - sideGutter * 2 - GlassBarHeight - BarItemGap, 380.dp)
            GlassBottomBar(
                nav = nav,
                pillWidth = pillWidth,
                onExitSearch = exitSearch,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = systemBottom + BarBottomGap),
            )
        }
    }
}
