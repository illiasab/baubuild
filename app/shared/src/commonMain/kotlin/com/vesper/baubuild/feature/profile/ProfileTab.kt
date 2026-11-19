package com.vesper.baubuild.feature.profile

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.model.ScoreTier
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.designsystem.*
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.github.ConnectGitHubScreen
import com.vesper.baubuild.feature.profile.edit.EditProfileScreen
import com.vesper.baubuild.feature.profile.lead.BecomeLeadScreen
import com.vesper.baubuild.feature.review.cardShadow

/** Entry point for this feature. Hosted by the Compose tab shell and by the native iOS TabView. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProfileTabRoot(modifier: Modifier = Modifier) {
    val vm = viewModel {
        ProfileViewModel(AppGraph.current.session, { com.vesper.baubuild.feature.auth.signOut() }, AppGraph.current.gitHubRepository.account)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.showingLead) { vm.closeBecomeLead() }
    when {
        // Connect GitHub owns its back handling; it can sit on top of Edit Profile ("Manage"), which keeps its form.
        state.showingGitHub -> ConnectGitHubScreen(onClose = vm::closeGitHub, modifier = modifier)
        state.showingLead -> BecomeLeadScreen(onClose = vm::closeBecomeLead, modifier = modifier)
        // Edit Profile owns its back handling (asks before discarding edits).
        state.editingProfile -> EditProfileScreen(
            onClose = vm::closeEditProfile, instanceKey = state.editSession, modifier = modifier,
            connectedGitHubLogin = state.gitHubAccount?.login, onManageGitHub = vm::openGitHub,
        )
        else -> ProfileScreen(
            state, onBecomeLead = vm::openBecomeLead, onSignOut = vm::signOut,
            onEditProfile = vm::openEditProfile, onGitHub = vm::openGitHub, modifier = modifier,
        )
    }
}

@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    onBecomeLead: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    onEditProfile: () -> Unit = {},
    onGitHub: () -> Unit = {},
) {
    val user = state.user
    Box(modifier.fillMaxSize().background(BauColors.Paper)) {
        if (user == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = BauColors.Ink)
            return@Box
        }
        val scroll = rememberScrollState()
        if (DebugLayout.startScrolledToBottom) LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .verticalScroll(scroll)
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = 12.dp)
                // Sign Out (last) comes to rest 24dp above the tab bar.
                .tabBarContentPadding(),
            verticalArrangement = Arrangement.spacedBy(BauSpacing.lg),
        ) {
            ScreenTitle("Profile", modifier = Modifier.semantics { heading() })
            Identity(user)
            BauSecondaryButton("Edit Profile", onClick = onEditProfile, modifier = Modifier.fillMaxWidth())
            ScoreCard(state.score, state.tier)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("My Earnings", "\$${user.earningsUsd}", Modifier.weight(1f))
                StatCard("Approved Projects", user.approvedProjects.toString(), Modifier.weight(1f))
            }
            DetailsCard(
                listOf(
                    DetailRow("Skills", user.skills.joinToString().ifBlank { "None yet" }),
                    DetailRow("Classes", user.classCodes.joinToString().ifBlank { "None yet" }),
                    state.gitHubLogin.let { login ->
                        if (login != null) DetailRow("GitHub", "@$login", onClick = onGitHub, clickLabel = "Manage GitHub")
                        else DetailRow("GitHub", "Connect", onClick = onGitHub, clickLabel = "Connect GitHub", isAction = true)
                    },
                    DetailRow("Role", user.role.label),
                ),
            )
            if (state.canBecomeLead) {
                BauPrimaryButton("Become a Lead", onClick = onBecomeLead, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp))
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BauDestructiveButton(
                    "Sign Out",
                    onClick = onSignOut,
                    loading = state.signingOut,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                )
                state.signOutError?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = BauColors.Warning, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun Identity(user: User) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        UserAvatar(user, size = 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(user.displayName, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
            if (user.bio.isNotBlank()) {
                Text(user.bio, fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary)
            }
        }
    }
}

/** Dark "BAU BUILD Score" card: tier gauge, "180 / 500" readout and My Status. */
@Composable
private fun ScoreCard(score: Int, tier: ScoreTier) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.22f), spotColor = BauColors.Ink.copy(alpha = 0.22f))
            .clip(shape).background(BauColors.Ink)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "BAU BUILD Score", modifier = Modifier.align(Alignment.Start),
            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Yellow,
        )
        ScoreGauge(score, Modifier.widthIn(max = 262.dp).fillMaxWidth().aspectRatio(300f / 166f))
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = "Score $score of ${ScoreTier.MAX_SCORE}. My status: ${tier.name}"
            },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(score.toString(), fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = BauColors.Surface)
                Text("/ ${ScoreTier.MAX_SCORE}", fontSize = 15.sp, color = GaugeMuted, modifier = Modifier.padding(bottom = 3.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("My Status", fontSize = 13.sp, color = GaugeMuted, modifier = Modifier.padding(bottom = 2.dp))
                Text(tier.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = BauColors.Surface)
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

/** One row of [DetailsCard]. [onClick] makes the whole row tappable (with a chevron); [isAction] tints the value blue. */
internal data class DetailRow(
    val label: String,
    val value: String,
    val onClick: (() -> Unit)? = null,
    val clickLabel: String? = null,
    val isAction: Boolean = false,
)

/** Grouped list of label / value rows (Skills, Classes, GitHub, Role). */
@Composable
private fun DetailsCard(rows: List<DetailRow>) {
    val shape = RoundedCornerShape(BauRadius.lg)
    Column(
        Modifier.fillMaxWidth().cardShadow().clip(shape).background(BauColors.Surface)
            .border(1.dp, BauColors.Line, shape),
    ) {
        rows.forEachIndexed { i, row ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp)
                    .then(
                        if (row.onClick != null) Modifier.clickable(onClickLabel = row.clickLabel, role = SemanticsRole.Button, onClick = row.onClick)
                        else Modifier,
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(row.label, fontSize = 16.sp, lineHeight = 21.sp, color = BauColors.Ink)
                Text(
                    row.value, modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                    fontSize = 16.sp, lineHeight = 21.sp,
                    fontWeight = if (row.isAction) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (row.isAction) BauColors.BlueDeep else BauColors.TextSecondary,
                )
                if (row.onClick != null) {
                    Text("›", fontSize = 22.sp, lineHeight = 21.sp, color = BauColors.TextTertiary, modifier = Modifier.clearAndSetSemantics { })
                }
            }
            if (i < rows.lastIndex) HorizontalDivider(color = BauColors.Line, thickness = 1.dp)
        }
    }
}

internal val Role.label: String get() = when (this) { Role.Student -> "Student"; Role.Lead -> "Lead"; Role.Staff -> "Staff" }
