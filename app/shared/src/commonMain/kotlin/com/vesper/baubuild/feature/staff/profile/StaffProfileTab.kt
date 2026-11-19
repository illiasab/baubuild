package com.vesper.baubuild.feature.staff.profile

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.SeniorEngineer
import com.vesper.baubuild.core.model.StaffProfile
import com.vesper.baubuild.core.notifications.rememberNotificationPermissionRequester
import com.vesper.baubuild.designsystem.*
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.profile.edit.EditProfileScreen
import com.vesper.baubuild.feature.review.cardShadow

/** Staff mobile entry point. Hosted by the staff tab shells (native iOS TabView / Compose on Android). */
@Composable
fun StaffProfileTabRoot(modifier: Modifier = Modifier) {
    val vm = viewModel {
        StaffProfileViewModel(AppGraph.current.session, AppGraph.current.staffRepository) {
            com.vesper.baubuild.feature.auth.signOut()
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val permission = rememberNotificationPermissionRequester()
    // Re-read the OS permission on every resume: the user may have changed it in Settings.
    LifecycleResumeEffect(permission) {
        vm.bindPermission(permission)
        onPauseOrDispose { }
    }
    if (state.editingProfile) {
        // Edit Profile owns its back handling (asks before discarding edits). Staff edit photo, name and bio only.
        EditProfileScreen(
            onClose = vm::closeEditProfile, instanceKey = "staff-${state.editSession}",
            modifier = modifier, showStudentFields = false,
        )
    } else {
        StaffProfileScreen(
            state,
            actions = StaffProfileActions(
                onEditProfile = vm::openEditProfile,
                onToggle = vm::setNotification,
                onOpenSettings = vm::openNotificationSettings,
                onRetry = vm::refresh,
                onSignOut = vm::signOut,
                onOpenEngineer = vm::openSeniorEngineer,
            ),
            modifier = modifier,
        )
        state.engineerSheet?.let { SeniorEngineerBioSheet(it, onDismiss = vm::closeSeniorEngineer) }
    }
}

internal class StaffProfileActions(
    val onEditProfile: () -> Unit,
    val onToggle: (StaffNotification, Boolean) -> Unit,
    val onOpenSettings: () -> Unit,
    val onRetry: () -> Unit,
    val onSignOut: () -> Unit,
    val onOpenEngineer: (SeniorEngineer) -> Unit = {},
)

@Composable
internal fun StaffProfileScreen(state: StaffProfileUiState, actions: StaffProfileActions, modifier: Modifier = Modifier) {
    val user = state.user
    Box(modifier.fillMaxSize().background(BauColors.Paper)) {
        if (user == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = BauColors.Ink)
            return@Box
        }
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = 12.dp)
                // Sign Out (last) comes to rest 24dp above the tab bar.
                .tabBarContentPadding(),
            verticalArrangement = Arrangement.spacedBy(BauSpacing.lg),
        ) {
            ScreenTitle("Profile", modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                UserAvatar(user, size = 64.dp)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.displayName, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
                    state.subtitle?.let { Text(it, fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary) }
                }
                EditPencilButton(onClick = actions.onEditProfile)
            }

            when (val p = state.profile) {
                LoadState.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BauColors.Ink)
                }
                is LoadState.Failed -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BauSpacing.md),
                ) {
                    Text(p.message, color = BauColors.TextSecondary, textAlign = TextAlign.Center)
                    BauSecondaryButton("Try Again", actions.onRetry)
                }
                is LoadState.Content -> {
                    if (p.stale) StaleBanner(actions.onRetry, Modifier.clip(RoundedCornerShape(BauRadius.md)))
                    ProfileBody(state, p.data, actions)
                }
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BauDestructiveButton(
                    "Sign Out", onClick = actions.onSignOut, loading = state.signingOut,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                )
                state.signOutError?.let {
                    Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.Warning, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun ProfileBody(state: StaffProfileUiState, profile: StaffProfile, actions: StaffProfileActions) {
    Column(verticalArrangement = Arrangement.spacedBy(BauSpacing.lg)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Projects Posted", profile.projectsPosted.toString(), Modifier.weight(1f))
            StatCard("Delivered", profile.delivered.toString(), Modifier.weight(1f))
        }
        StatCard("Raised Money", formatUsd(profile.raisedUsd), Modifier.fillMaxWidth())

        Section("Details") {
            ValueRow("Department", profile.department.ifBlank { "—" })
            Divider()
            ValueRow("Role", "Staff")
            Divider()
            ValueRow("Classes", state.classesLabel)
        }

        Section("Notifications") {
            StaffNotification.entries.forEachIndexed { index, kind ->
                if (index > 0) Divider()
                ToggleRow(
                    label = kind.label,
                    checked = state.isOn(kind),
                    enabled = state.busy == null && !state.signingOut,
                    onChange = { actions.onToggle(kind, it) },
                )
            }
        }
        if (state.notificationsBlocked || state.notificationError != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics(mergeDescendants = false) {
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                state.notificationError?.let {
                    Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.Danger)
                }
                if (state.notificationsBlocked) {
                    Text(
                        "Notifications are off for BAU BUILD in Settings",
                        fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
                    )
                    BauTextLink(
                        "Open Settings", onClick = actions.onOpenSettings,
                        color = BauColors.BlueDeep, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.offset(x = (-4).dp),
                    )
                }
            }
        }
        // Hidden when the backend has none (e.g. `GET /v1/senior-engineers` not shipped yet).
        if (state.seniorEngineers.isNotEmpty()) {
            Section("Senior Engineers") {
                state.seniorEngineers.forEachIndexed { index, engineer ->
                    if (index > 0) Divider()
                    SeniorEngineerRow(engineer, onClick = { actions.onOpenEngineer(engineer) })
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    BauCard(modifier.cardShadow().semantics(mergeDescendants = true) { }) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
    }
}

/** "Details" caption above a grouped white card. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary,
        )
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.fillMaxWidth().cardShadow().clip(shape).background(BauColors.Surface).border(1.dp, BauColors.Line, shape),
            content = content,
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = BauColors.Line, thickness = 1.dp)

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, fontSize = 16.sp, lineHeight = 21.sp, color = BauColors.Ink)
        Text(
            value, modifier = Modifier.weight(1f), textAlign = TextAlign.End,
            fontSize = 16.sp, lineHeight = 21.sp, color = BauColors.TextSecondary,
        )
    }
}

/** Whole row toggles (≥ 52dp touch target); the switch itself is decorative. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp, color = BauColors.Ink)
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

/** 4820 → "$4,820". */
internal fun formatUsd(amount: Int): String {
    val digits = kotlin.math.abs(amount).toString().reversed().chunked(3).joinToString(",").reversed()
    return (if (amount < 0) "-$" else "$") + digits
}

/** Compact "Edit Profile" action: a pencil in a 44dp circle on the right of the profile header. */
@Composable
private fun EditPencilButton(onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(BauColors.Surface)
            .border(1.dp, BauColors.Line, androidx.compose.foundation.shape.CircleShape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Edit profile", onClick = onClick)
            .semantics { contentDescription = "Edit profile" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            // Pencil body (diagonal), tip at bottom-left, plus the eraser cap line.
            val body = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.70f, w * 0.10f)
                lineTo(w * 0.90f, w * 0.30f)
                lineTo(w * 0.32f, w * 0.88f)
                lineTo(w * 0.10f, w * 0.90f)
                lineTo(w * 0.12f, w * 0.68f)
                close()
                moveTo(w * 0.58f, w * 0.22f)
                lineTo(w * 0.78f, w * 0.42f)
            }
            drawPath(body, BauColors.Ink, style = stroke)
        }
    }
}
