package com.vesper.baubuild.shell.staff

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.LocalTabBarInset
import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.StaffNavEvent
import com.vesper.baubuild.feature.staff.StaffTab
import com.vesper.baubuild.feature.staff.inquiries.StaffInquiriesTabRoot
import com.vesper.baubuild.feature.staff.newinquiry.NewInquiryRoot
import com.vesper.baubuild.feature.staff.overview.StaffOverviewTabRoot
import com.vesper.baubuild.feature.staff.profile.StaffProfileTabRoot
import com.vesper.baubuild.feature.staff.tour.StaffTour
import com.vesper.baubuild.feature.staff.tour.StaffTourHost
import com.vesper.baubuild.feature.staff.tour.StaffTourStep
import com.vesper.baubuild.feature.staff.tour.staffTourTarget
import com.vesper.baubuild.shell.BarBottomGap
import com.vesper.baubuild.shell.BarItemGap
import com.vesper.baubuild.shell.GlassBarHeight
import com.vesper.baubuild.shell.GlassTabItem
import com.vesper.baubuild.shell.glassSurface

private class StaffTabSpec(val tab: StaffTab, val label: String, val icon: ImageVector)

private val StaffTabs = listOf(
    StaffTabSpec(StaffTab.Overview, "Overview", StaffIcons.Overview),
    StaffTabSpec(StaffTab.Inquiries, "Inquiries", StaffIcons.Inquiries),
    StaffTabSpec(StaffTab.Profile, "Profile", StaffIcons.Profile),
)

private const val NewInquiryStateKey = "newInquiry"

/**
 * Staff (@bau.edu) mobile shell for Android (iOS: StaffTabShell.swift; desktop: portal). Floating glass pill with
 * Overview / Inquiries / Profile + a separate round yellow "+" on its right (where students have Search). "+" opens
 * [NewInquiryRoot] as a full-screen overlay above the tabs (system back closes it unless the flow handles back
 * itself). Each tab keeps its saveable state while hidden. Collects [StaffNav.events] and runs the first-run
 * [StaffTour] (banner + spotlights via [StaffTourHost]).
 */
@Suppress("DEPRECATION") // ui-backhandler BackHandler, same as the student shell.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun StaffShell(user: User) {
    val nav = rememberSaveable(saver = StaffShellNavigation.Saver) { StaffShellNavigation() }
    val states = rememberSaveableStateHolder()

    val openNewInquiry: () -> Unit = {
        StaffTour.advanceFrom(StaffTourStep.TapPlus)
        nav.openNewInquiry()
    }
    val closeNewInquiry: () -> Unit = {
        if (nav.closeNewInquiry()) {
            states.removeState(NewInquiryStateKey) // the next "+" starts a fresh draft
            StaffTour.onNewInquiryDismissed()
        }
    }
    val currentOpen by rememberUpdatedState(openNewInquiry)

    LaunchedEffect(user.id) { StaffTour.startIfFirstTime(user.id) }
    LaunchedEffect(Unit) {
        StaffNav.events.collect { event ->
            when (event) {
                StaffNavEvent.OpenNewInquiry -> currentOpen()
                is StaffNavEvent.SelectTab -> {
                    closeNewInquiry()
                    nav.select(event.tab)
                }
            }
        }
    }

    StaffTourHost(showBanner = true) {
        BoxWithConstraints(Modifier.fillMaxSize().background(BauColors.Paper)) {
            // Same bottom-inset contract as the student shell: content runs under the bar; screens pad by
            // LocalTabBarInset + 24dp (designsystem/BottomBarSpacing.kt). The bar stays behind the keyboard.
            val systemBottom = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
                .only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
            val covered = nav.isNewInquiryOpen
            val sideGutter = 20.dp
            val pillWidth = min(maxWidth - sideGutter * 2 - GlassBarHeight - BarItemGap, 380.dp)
            Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics { } else Modifier)) {
                CompositionLocalProvider(LocalTabBarInset provides systemBottom + BarBottomGap + GlassBarHeight) {
                    Box(Modifier.fillMaxSize()) {
                        states.SaveableStateProvider(nav.selected.name) {
                            when (nav.selected) {
                                StaffTab.Overview -> StaffOverviewTabRoot()
                                StaffTab.Inquiries -> StaffInquiriesTabRoot()
                                StaffTab.Profile -> StaffProfileTabRoot()
                            }
                        }
                    }
                }
                StaffBottomBar(
                    selected = nav.selected,
                    onSelect = nav::select,
                    onNewInquiry = openNewInquiry,
                    pillWidth = pillWidth,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = systemBottom + BarBottomGap),
                )
            }

            // Composed after the tabs, so it draws on top; the flow's own back handlers (inside) win over this one.
            AnimatedVisibility(
                visible = nav.isNewInquiryOpen,
                enter = slideInVertically(tween(320)) { it / 3 } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(260)) { it / 3 } + fadeOut(tween(200)),
            ) {
                BackHandler(onBack = closeNewInquiry)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(BauColors.Paper)
                        // In the hit path, so taps never reach the tabs underneath.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                ) {
                    states.SaveableStateProvider(NewInquiryStateKey) {
                        NewInquiryRoot(onClose = closeNewInquiry)
                    }
                }
            }
        }
    }
}

@Composable
private fun StaffBottomBar(
    selected: StaffTab,
    onSelect: (StaffTab) -> Unit,
    onNewInquiry: () -> Unit,
    pillWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(GlassBarHeight / 2)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .width(pillWidth)
                .height(GlassBarHeight)
                .glassSurface(shape)
                .padding(4.dp)
                .selectableGroup(),
        ) {
            StaffTabs.forEach { spec ->
                val isSelected = selected == spec.tab
                GlassTabItem(
                    label = spec.label,
                    icon = spec.icon,
                    selected = isSelected,
                    onClick = { onSelect(spec.tab) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            // Tour step 5 points at the Inquiries tab until it's open; then the list's row takes over.
                            if (spec.tab == StaffTab.Inquiries && !isSelected) {
                                Modifier.staffTourTarget(StaffTourStep.OpenInquiry, cornerRadius = 28.dp)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.width(BarItemGap))
        NewInquiryButton(onClick = onNewInquiry, Modifier.staffTourTarget(StaffTourStep.TapPlus, cornerRadius = GlassBarHeight / 2))
    }
}

/** The round yellow "+" (design: 64dp, Yellow, Ink glyph, soft shadow). */
@Composable
private fun NewInquiryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(GlassBarHeight)
            .shadow(14.dp, CircleShape, ambientColor = BauColors.Ink.copy(alpha = 0.16f), spotColor = BauColors.Ink.copy(alpha = 0.16f))
            .clip(CircleShape)
            .background(BauColors.Yellow)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "New Inquiry"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(StaffIcons.Plus, contentDescription = null, tint = BauColors.Ink, modifier = Modifier.size(26.dp))
    }
}
