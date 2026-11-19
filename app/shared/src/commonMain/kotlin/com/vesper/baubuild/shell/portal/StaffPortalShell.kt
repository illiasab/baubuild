package com.vesper.baubuild.shell.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.UserAvatar
import com.vesper.baubuild.feature.staff.StaffNav
import com.vesper.baubuild.feature.staff.StaffNavEvent
import com.vesper.baubuild.feature.staff.StaffTab
import com.vesper.baubuild.feature.staff.portal.NewInquiryPortalPage
import com.vesper.baubuild.feature.staff.portal.StaffInquiriesPortalPage
import com.vesper.baubuild.feature.staff.portal.StaffOverviewPortalPage
import com.vesper.baubuild.feature.staff.portal.StaffProfilePortalPage
import com.vesper.baubuild.feature.staff.portal.staffProfilePortalViewModel
import com.vesper.baubuild.feature.staff.tour.StaffTour
import com.vesper.baubuild.feature.staff.tour.StaffTourHost
import com.vesper.baubuild.feature.staff.tour.StaffTourStep
import com.vesper.baubuild.feature.staff.tour.staffTourTarget
import com.vesper.baubuild.shell.staff.StaffIcons

/** Sections of the staff portal sidebar, in sidebar order (Ctrl/⌘+1..4). */
internal enum class StaffPortalSection(val label: String) {
    Overview("Overview"), Inquiries("Inquiries"), NewInquiry("New Inquiry"), Profile("Profile");

    val icon: ImageVector
        get() = when (this) {
            Overview -> StaffIcons.Overview
            Inquiries -> StaffIcons.Inquiries
            NewInquiry -> StaffIcons.Plus
            Profile -> StaffIcons.Profile
        }

    companion object {
        /** The section a [StaffNav] request lands on. */
        fun of(event: StaffNavEvent): StaffPortalSection = when (event) {
            StaffNavEvent.OpenNewInquiry -> NewInquiry
            is StaffNavEvent.SelectTab -> of(event.tab)
        }

        fun of(tab: StaffTab): StaffPortalSection = when (tab) {
            StaffTab.Overview -> Overview
            StaffTab.Inquiries -> Inquiries
            StaffTab.Profile -> Profile
        }

        /** Ctrl/⌘ + 1..4. */
        fun ofShortcut(key: Key): StaffPortalSection? = when (key) {
            Key.One, Key.NumPad1 -> Overview
            Key.Two, Key.NumPad2 -> Inquiries
            Key.Three, Key.NumPad3 -> NewInquiry
            Key.Four, Key.NumPad4 -> Profile
            else -> null
        }
    }
}

/** What the portal's tour banner shows for a [StaffTourStep]: "Step N of 5" + message (design: docs__Portal_Staff_*). */
internal data class StaffPortalTourBanner(val number: Int, val message: String) {
    companion object {
        /** The portal tour has 5 steps; the mobile tour's two New Inquiry steps are one page (step 2) here. */
        const val COUNT = 5

        fun of(step: StaffTourStep): StaffPortalTourBanner = when (step) {
            StaffTourStep.TapPlus -> StaffPortalTourBanner(1, "Post work for students from New Inquiry.")
            StaffTourStep.DescribeWork, StaffTourStep.RewardAndTiming ->
                StaffPortalTourBanner(2, "Describe the work, then tap Refine with AI.")
            StaffTourStep.ReadyToPost -> StaffPortalTourBanner(3, "Check the AI’s version, then tap Post project.")
            StaffTourStep.OpenInquiry -> StaffPortalTourBanner(4, "Open any inquiry to follow its daily progress.")
            StaffTourStep.Details -> StaffPortalTourBanner(5, "Blockers show up here. Help the team clear them.")
        }
    }
}

/**
 * Signed-in desktop layout for @bau.edu staff, in the Web portal design (docs__Portal_Staff_*.html): dark 260dp
 * sidebar (brand, Overview / Inquiries / New Inquiry / Profile, user card) and the selected page to the right. Each
 * page keeps its saveable state (and ViewModel) across section switches, so a half-written inquiry survives a visit
 * to another section.
 *
 * Navigation requests from staff pages arrive through [StaffNav.events]. The first-run [StaffTour] is shared with the
 * mobile shells; the portal shows it as "Step N of 5" above the page ([StaffPortalTourBanner]) and spotlights the
 * sidebar entries for steps 1 (New Inquiry) and 4 (Inquiries).
 */
@Composable
internal fun StaffPortalShell(user: User) {
    // The staff pages' ViewModels (half-written inquiry, profile…) belong to this signed-in session: they survive
    // section switches but are cleared on sign-out / user switch instead of living on in the window's store.
    val owner = remember(user.id) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        StaffPortalShellContent(user)
    }
}

@Composable
private fun StaffPortalShellContent(user: User) {
    var selectedName by rememberSaveable { mutableStateOf(StaffPortalSection.Overview.name) }
    val selected = StaffPortalSection.valueOf(selectedName)
    val pageStates = rememberSaveableStateHolder()
    val tourStep by StaffTour.step.collectAsStateWithLifecycle()

    LaunchedEffect(user.id) { StaffTour.startIfFirstTime(user.id) }

    /** [byUser]: sidebar click / shortcut. Leaving New Inquiry that way mid-tour points the tour back at step 1. */
    val select: (StaffPortalSection, Boolean) -> Unit = { section, byUser ->
        if (byUser && selectedName == StaffPortalSection.NewInquiry.name && section != StaffPortalSection.NewInquiry) {
            StaffTour.onNewInquiryDismissed()
        }
        selectedName = section.name
    }
    val currentSelect by rememberUpdatedState(select)

    LaunchedEffect(Unit) {
        StaffNav.events.collect { event ->
            // Posting an inquiry is what sends New Inquiry → Inquiries; the tour moves on to step 4 with it.
            if (event == StaffNavEvent.SelectTab(StaffTab.Inquiries)) StaffTour.advanceFrom(StaffTourStep.ReadyToPost)
            currentSelect(StaffPortalSection.of(event), false)
        }
    }

    DisposableEffect(Unit) {
        PortalKeyboard.previewHandler = handler@{ event ->
            if (event.type != KeyEventType.KeyDown) return@handler false
            if (!(event.isMetaPressed || event.isCtrlPressed) || event.isAltPressed) return@handler false
            val section = StaffPortalSection.ofShortcut(event.key) ?: return@handler false
            currentSelect(section, true)
            true
        }
        PortalKeyboard.fallbackHandler = null
        onDispose {
            PortalKeyboard.previewHandler = null
            PortalKeyboard.fallbackHandler = null
        }
    }

    val profileVm = staffProfilePortalViewModel()
    val profileState by profileVm.state.collectAsStateWithLifecycle()
    val title = profileState.profile.dataOrNull?.title?.trim()?.takeIf { it.isNotEmpty() }
    val displayName = profileState.displayName.ifBlank { user.displayName }

    var bannerHeightPx by remember { mutableIntStateOf(0) }
    val banner = tourStep?.let(StaffPortalTourBanner::of)

    Box(Modifier.fillMaxSize().background(BauColors.Paper)) {
        StaffTourHost(showBanner = false) {
            Row(Modifier.fillMaxSize()) {
                StaffSidebar(
                    selected = selected,
                    user = user,
                    displayName = displayName,
                    roleLine = listOfNotNull(title, "Staff").joinToString(" · "),
                    onSelect = { select(it, true) },
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // Keeps the page below the banner (drawn above the tour's dim, outside the host).
                    if (banner != null) Spacer(Modifier.height(with(LocalDensity.current) { bannerHeightPx.toDp() }))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        pageStates.SaveableStateProvider(selected.name) {
                            when (selected) {
                                StaffPortalSection.Overview -> StaffOverviewPortalPage()
                                StaffPortalSection.Inquiries -> StaffInquiriesPortalPage()
                                StaffPortalSection.NewInquiry -> NewInquiryPortalPage()
                                StaffPortalSection.Profile -> StaffProfilePortalPage()
                            }
                        }
                    }
                }
            }
        }
        if (banner != null) {
            StaffPortalTourBannerView(
                banner,
                onSkip = StaffTour::skip,
                modifier = Modifier
                    .padding(start = SidebarWidth)
                    .onSizeChanged { bannerHeightPx = it.height },
            )
        }
    }
}

@Composable
private fun StaffSidebar(
    selected: StaffPortalSection,
    user: User,
    displayName: String,
    roleLine: String,
    onSelect: (StaffPortalSection) -> Unit,
) {
    Column(
        Modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .background(PortalColors.Sidebar)
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        BrandRow()
        Spacer(Modifier.height(28.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StaffPortalSection.entries.forEachIndexed { index, section ->
                val tourTarget = when (section) {
                    StaffPortalSection.NewInquiry -> StaffTourStep.TapPlus
                    StaffPortalSection.Inquiries -> StaffTourStep.OpenInquiry
                    else -> null
                }
                StaffNavItem(
                    section = section,
                    selected = section == selected,
                    shortcut = shortcutLabel("${index + 1}"),
                    onClick = { onSelect(section) },
                    // Spotlight the entry only while it isn't the open page (the page then has its own target).
                    modifier = if (tourTarget != null && section != selected) {
                        Modifier.staffTourTarget(tourTarget, cornerRadius = 8.dp)
                    } else Modifier,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        StaffUserCard(user, displayName, roleLine, onClick = { onSelect(StaffPortalSection.Profile) })
    }
}

@Composable
private fun StaffNavItem(
    section: StaffPortalSection,
    selected: Boolean,
    shortcut: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (selected || hovered) BauColors.Yellow else PortalColors.IdleText
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PortalColors.SelectedFill else Color.Transparent)
            .hoverable(interaction)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(section.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            section.label,
            color = tint,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (hovered) Text(shortcut, color = PortalColors.IdleText.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

/** "NH · Dr. Nora Hale · Professor · Staff"; opens Profile. */
@Composable
private fun StaffUserCard(user: User, displayName: String, roleLine: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PortalColors.Card)
            .border(1.dp, PortalColors.CardBorder, shape)
            .clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UserAvatar(user, size = 36.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                displayName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(roleLine, color = PortalColors.IdleText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Dark "Step N of 5 · message · Skip tour" bar above the page (design: 20/32 margin, 12dp radius). */
@Composable
private fun StaffPortalTourBannerView(banner: StaffPortalTourBanner, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 20.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .background(BauColors.Ink, shape)
                .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
                .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Step ${banner.number} of ${StaffPortalTourBanner.COUNT}",
                color = BauColors.Ink,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(BauColors.Yellow, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Text(
                banner.message,
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = if (hovered) 0.24f else 0.16f))
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Skip the tour", onClick = onSkip)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Skip tour", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
