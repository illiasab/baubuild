package com.vesper.baubuild.feature.staff.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.SeniorEngineer
import com.vesper.baubuild.core.model.StaffProfile
import com.vesper.baubuild.core.notifications.rememberNotificationPermissionRequester
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauDestructiveButton
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.StaleBanner
import com.vesper.baubuild.designsystem.UserAvatar
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.profile.edit.rememberEditProfileViewModel
import com.vesper.baubuild.feature.profile.portal.EditProfileDialog
import com.vesper.baubuild.feature.review.portal.PortalButton
import com.vesper.baubuild.feature.review.portal.PortalCard
import com.vesper.baubuild.feature.review.portal.PortalCardStrip
import com.vesper.baubuild.feature.review.portal.PortalPageHeader
import com.vesper.baubuild.feature.review.portal.PortalSectionTitle
import com.vesper.baubuild.feature.review.portal.PortalTokens
import com.vesper.baubuild.feature.review.portal.pointerHand
import com.vesper.baubuild.feature.staff.profile.StaffNotification
import com.vesper.baubuild.feature.staff.profile.StaffProfileUiState
import com.vesper.baubuild.feature.staff.profile.StaffProfileViewModel
import com.vesper.baubuild.feature.staff.profile.formatUsd

/**
 * The staff Profile ViewModel for the desktop portal. Same store key as the default `viewModel {}` key, so the
 * sidebar user card ("Dr. Nora Hale · Professor · Staff") and this page share one instance.
 */
@Composable
internal fun staffProfilePortalViewModel(): StaffProfileViewModel = viewModel {
    StaffProfileViewModel(AppGraph.current.session, AppGraph.current.staffRepository) {
        com.vesper.baubuild.feature.auth.signOut()
    }
}

/**
 * Desktop staff Profile (docs__Portal_Staff_Profile.html): identity card with "Edit profile" (the portal Edit Profile
 * dialog without the student fields), stats, DETAILS and NOTIFICATIONS cards, Sign Out.
 */
@Composable
fun StaffProfilePortalPage(modifier: Modifier = Modifier) {
    val vm = staffProfilePortalViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val permission = rememberNotificationPermissionRequester()
    // Re-read the OS permission whenever the window comes back: it may have been changed in System Settings.
    LifecycleResumeEffect(permission) {
        vm.bindPermission(permission)
        onPauseOrDispose { }
    }

    StaffProfilePortalContent(
        state,
        onEditProfile = vm::openEditProfile,
        onToggle = vm::setNotification,
        onOpenSettings = vm::openNotificationSettings,
        onRetry = vm::refresh,
        onSignOut = vm::signOut,
        onOpenEngineer = vm::openSeniorEngineer,
        modifier = modifier,
    )

    state.engineerSheet?.let { SeniorEngineerPortalDialog(it, onClose = vm::closeSeniorEngineer) }

    if (state.editingProfile && state.user != null) {
        // A new key per opening so the form starts from the current user.
        val editVm = rememberEditProfileViewModel("staff-portal-${state.editSession}")
        EditProfileDialog(editVm, onClose = vm::closeEditProfile, showStudentFields = false)
    }
}

/** Below this content width the DETAILS / NOTIFICATIONS cards stack (design: two 380px-min columns + 20 gap). */
private val TwoCardsMinWidth = 780.dp
/** Below this the three stat cards stack (design: 200px-min grid cells + 16 gap). */
private val ThreeStatsMinWidth = 632.dp

@Composable
private fun StaffProfilePortalContent(
    state: StaffProfileUiState,
    onEditProfile: () -> Unit,
    onToggle: (StaffNotification, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onOpenEngineer: (SeniorEngineer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val user = state.user
    Column(modifier.fillMaxSize().background(BauColors.Paper)) {
        PortalPageHeader("Profile")
        if (user == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = BauColors.Ink) }
            return@Column
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = PortalTokens.PagePadding, end = PortalTokens.PagePadding, bottom = PortalTokens.PagePadding),
        ) {
            val wide = maxWidth >= TwoCardsMinWidth
            val statsInRow = maxWidth >= ThreeStatsMinWidth
            Column(verticalArrangement = Arrangement.spacedBy(PortalTokens.Gap)) {
                PortalCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        UserAvatar(user, size = 72.dp)
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PortalSectionTitle(state.displayName, fontSize = 24)
                            state.subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary)
                            }
                        }
                        PortalButton("Edit profile", onClick = onEditProfile)
                    }
                }

                when (val p = state.profile) {
                    LoadState.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BauColors.Ink)
                    }
                    is LoadState.Failed -> PortalCard(Modifier.fillMaxWidth(), gap = 12.dp) {
                        Text(p.message, fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary)
                        PortalButton("Try again", onClick = onRetry)
                    }
                    is LoadState.Content -> {
                        if (p.stale) StaleBanner(onRetry, Modifier.clip(RoundedCornerShape(PortalTokens.CardRadius)))
                        StatsRow(p.data, statsInRow)
                        if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(PortalTokens.Gap), verticalAlignment = Alignment.Top) {
                                DetailsCard(state, p.data, Modifier.weight(1f))
                                NotificationsCard(state, onToggle, onOpenSettings, onOpenEngineer, Modifier.weight(1f))
                            }
                        } else {
                            DetailsCard(state, p.data, Modifier.fillMaxWidth())
                            NotificationsCard(state, onToggle, onOpenSettings, onOpenEngineer, Modifier.fillMaxWidth())
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BauDestructiveButton(
                        "Sign Out", onClick = onSignOut, loading = state.signingOut,
                        modifier = Modifier.widthIn(min = 180.dp).pointerHand(),
                    )
                    state.signOutError?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.Warning) }
                }
            }
        }
    }
}

@Composable
private fun StatsRow(profile: StaffProfile, inRow: Boolean) {
    val stats = listOf(
        "Projects posted" to profile.projectsPosted.toString(),
        "Delivered" to profile.delivered.toString(),
        "Raised money" to formatUsd(profile.raisedUsd),
    )
    if (inRow) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            stats.forEach { (label, value) -> StatCard(label, value, Modifier.weight(1f).fillMaxHeight()) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            stats.forEach { (label, value) -> StatCard(label, value, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    PortalCard(modifier.semantics(mergeDescendants = true) { }, padding = 0.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
            Text(value, fontFamily = DisplayFamily, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
        }
    }
}

@Composable
private fun DetailsCard(state: StaffProfileUiState, profile: StaffProfile, modifier: Modifier) {
    PortalCard(modifier, padding = 0.dp) {
        PortalCardStrip("DETAILS")
        ValueRow("Department", profile.department.ifBlank { "—" })
        RowDivider()
        ValueRow("Role", "Staff")
        RowDivider()
        ValueRow("Classes", state.classesLabel)
    }
}

@Composable
private fun NotificationsCard(
    state: StaffProfileUiState,
    onToggle: (StaffNotification, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEngineer: (SeniorEngineer) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PortalCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            PortalCardStrip("NOTIFICATIONS")
            StaffNotification.entries.forEachIndexed { index, kind ->
                if (index > 0) RowDivider()
                ToggleRow(
                    label = kind.label,
                    checked = state.isOn(kind),
                    enabled = state.busy == null && !state.signingOut,
                    onChange = { onToggle(kind, it) },
                )
            }
            // Each name opens that engineer's bio; hidden when the backend lists none.
            if (state.seniorEngineers.isNotEmpty()) {
                RowDivider()
                SeniorEngineersPortalRow(state.seniorEngineers, onOpen = onOpenEngineer)
            }
        }
        if (state.notificationsBlocked || state.notificationError != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = false) {
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                state.notificationError?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.Danger) }
                if (state.notificationsBlocked) {
                    Text(
                        "Notifications are off for BAU BUILD in System Settings",
                        fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
                    )
                    BauTextLink(
                        "Open Settings", onClick = onOpenSettings, color = BauColors.BlueDeep, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.offset(x = (-4).dp).pointerHand(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
        Text(
            value, modifier = Modifier.weight(1f), textAlign = TextAlign.End,
            fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary,
        )
    }
}

/** Whole row toggles (click or Space when focused); hover washes the row and shows the hand cursor. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .background(if (hovered && enabled) PortalTokens.RowHover else Color.Transparent)
            .hoverable(source)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, interactionSource = source, indication = null, onValueChange = onChange)
            .then(if (enabled) Modifier.pointerHand() else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.Ink)
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = BauColors.BlueDeep, checkedThumbColor = BauColors.Surface, checkedBorderColor = BauColors.BlueDeep,
                uncheckedTrackColor = SwitchOff, uncheckedThumbColor = BauColors.Surface, uncheckedBorderColor = SwitchOff,
                disabledCheckedTrackColor = BauColors.BlueDeep.copy(alpha = 0.6f), disabledCheckedThumbColor = BauColors.Surface,
                disabledUncheckedTrackColor = SwitchOff.copy(alpha = 0.6f), disabledUncheckedThumbColor = BauColors.Surface,
                disabledUncheckedBorderColor = SwitchOff.copy(alpha = 0.6f), disabledCheckedBorderColor = BauColors.BlueDeep.copy(alpha = 0.6f),
            ),
        )
    }
}

private val SwitchOff = Color(0xFFC5CAD3)
