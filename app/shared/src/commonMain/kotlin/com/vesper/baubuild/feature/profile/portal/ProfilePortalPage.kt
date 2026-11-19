package com.vesper.baubuild.feature.profile.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.vesper.baubuild.designsystem.UserAvatar
import com.vesper.baubuild.feature.profile.edit.rememberEditProfileViewModel
import com.vesper.baubuild.feature.profile.edit.ConnectedGitHub
import com.vesper.baubuild.feature.github.ConnectGitHubDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.model.ScoreTier
import com.vesper.baubuild.core.model.User
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauDestructiveButton
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.profile.ProfileUiState
import com.vesper.baubuild.feature.profile.ProfileViewModel
import com.vesper.baubuild.feature.profile.ScoreGauge
import com.vesper.baubuild.feature.profile.label
import com.vesper.baubuild.feature.profile.lead.BecomeLeadUiState
import com.vesper.baubuild.feature.profile.lead.BecomeLeadViewModel
import com.vesper.baubuild.feature.review.portal.*

/** Desktop page in the Web portal design. Hosted by the portal shell's content area (right of the sidebar). */
@Composable
fun ProfilePortalPage(modifier: Modifier = Modifier) {
    val profileVm = viewModel {
        ProfileViewModel(AppGraph.current.session, { com.vesper.baubuild.feature.auth.signOut() }, AppGraph.current.gitHubRepository.account)
    }
    val leadVm = viewModel { BecomeLeadViewModel() }
    val profile by profileVm.state.collectAsStateWithLifecycle()
    val lead by leadVm.state.collectAsStateWithLifecycle()
    // Each opening gets a new key so the form starts from the current user (the ViewModel store outlives the dialog).
    var editSession by rememberSaveable { mutableStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val openEditor = { if (!editing) { editSession++; editing = true } }
    var connectingGitHub by rememberSaveable { mutableStateOf(false) }
    val openGitHub = { connectingGitHub = true }

    ProfilePortalContent(
        profile = profile,
        lead = lead,
        onEditProfile = openEditor,
        onConnectGitHub = openGitHub,
        onStartExam = leadVm::startExam,
        onSignOut = profileVm::signOut,
        modifier = modifier,
    )
    if (editing && profile.user != null) {
        val editVm = rememberEditProfileViewModel("portal-$editSession")
        EditProfileDialog(
            editVm, onClose = { editing = false },
            connectedGitHub = profile.gitHubAccount?.let { ConnectedGitHub(it.login, onManage = openGitHub) },
        )
    }
    // Opened from the GitHub row or from Edit profile → Manage (stacks above the editor).
    if (connectingGitHub) ConnectGitHubDialog(onDismiss = { connectingGitHub = false })
}

/** Below this content width the two columns stack (design: 460 + 20 + 340). */
private val TwoColumnMinWidth = 820.dp
private val RightColumnWidth = 340.dp

@Composable
internal fun ProfilePortalContent(
    profile: ProfileUiState,
    lead: BecomeLeadUiState,
    onEditProfile: () -> Unit,
    onStartExam: () -> Unit,
    onConnectGitHub: () -> Unit = onEditProfile,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val user = profile.user
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
            val left: @Composable ColumnScope.() -> Unit = {
                IdentityCard(user, onEditProfile)
                ProfessionalCard(user, profile.gitHubAccount?.login, onConnectGithub = onConnectGitHub)
                if (profile.canBecomeLead) BecomeLeadCard(lead, onStartExam)
                SignOutBlock(profile, onSignOut)
            }
            val right: @Composable ColumnScope.() -> Unit = {
                ScoreCard(profile.score, profile.tier)
                StatsRow(user)
            }
            if (maxWidth >= TwoColumnMinWidth) {
                Row(horizontalArrangement = Arrangement.spacedBy(PortalTokens.Gap), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PortalTokens.Gap), content = left)
                    Column(Modifier.width(RightColumnWidth), verticalArrangement = Arrangement.spacedBy(PortalTokens.Gap), content = right)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(PortalTokens.Gap)) {
                    right()
                    left()
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(user: User, onEditProfile: () -> Unit) {
    PortalCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            UserAvatar(user, size = 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PortalSectionTitle(user.displayName, fontSize = 24)
                if (user.bio.isNotBlank()) {
                    Text(user.bio, fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary)
                } else {
                    Text("No bio yet. Tell leads what you like to build.", fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextTertiary)
                }
            }
            PortalButton("Edit profile", onClick = onEditProfile)
        }
    }
}

@Composable
private fun ProfessionalCard(user: User, gitHubLogin: String?, onConnectGithub: () -> Unit) {
    val rows = professionalRows(user, gitHubLogin)
    PortalCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        PortalCardStrip("PROFESSIONAL")
        rows.forEachIndexed { i, row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    Modifier.weight(1f).semantics(mergeDescendants = true) { },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(row.label, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
                    Text(
                        row.value, modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                        fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary,
                    )
                }
                if (row.connectAction) PortalTextAction("Connect", onClickLabel = "Connect GitHub", onClick = onConnectGithub)
                if (row.manageAction) PortalTextAction("Manage", onClickLabel = "Manage GitHub", onClick = onConnectGithub)
            }
            if (i < rows.lastIndex) HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)
        }
    }
}

/**
 * One label/value row of the "PROFESSIONAL" card. [connectAction] shows "Connect", [manageAction] shows "Manage";
 * both open the Connect GitHub dialog.
 */
internal data class ProfessionalRow(
    val label: String,
    val value: String,
    val connectAction: Boolean = false,
    val manageAction: Boolean = false,
)

/**
 * Skills / Classes / GitHub / Role rows. Empty lists read "None yet", like the mobile Profile.
 * GitHub reads "Connected" only for the token-connected account's [gitHubLogin]; a saved username alone offers Connect.
 */
internal fun professionalRows(user: User, gitHubLogin: String? = null): List<ProfessionalRow> {
    // "Connected" only for a live token connection; a saved username alone still offers Connect.
    val connected = gitHubLogin?.trim()?.removePrefix("@").orEmpty()
    val saved = user.githubUsername?.trim()?.removePrefix("@").orEmpty()
    return listOf(
        ProfessionalRow("Skills", user.skills.joinToString().ifBlank { "None yet" }),
        ProfessionalRow("Classes", user.classCodes.joinToString().ifBlank { "None yet" }),
        when {
            connected.isNotEmpty() -> ProfessionalRow("GitHub", "Connected · @$connected", manageAction = true)
            saved.isNotEmpty() -> ProfessionalRow("GitHub", "@$saved · Not connected", connectAction = true)
            else -> ProfessionalRow("GitHub", "Not connected", connectAction = true)
        },
        ProfessionalRow("Role", user.role.label),
    )
}

/** Small blue text action ("Connect"): underline + hand cursor on hover, 44dp tall hit area. */
@Composable
private fun PortalTextAction(text: String, onClickLabel: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(PortalTokens.ButtonRadius))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .pointerHand()
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Blue,
            textDecoration = if (hovered) TextDecoration.Underline else null,
        )
    }
}

@Composable
private fun BecomeLeadCard(lead: BecomeLeadUiState, onStartExam: () -> Unit) {
    PortalCard(Modifier.fillMaxWidth(), gap = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PortalSectionTitle("Become a Lead")
                Text(
                    "Leads review student code and decide what gets approved.",
                    fontSize = 15.sp, lineHeight = 22.sp, color = BauColors.TextSecondary,
                )
            }
            PortalButton("Start proctored exam", onClick = onStartExam, style = PortalButtonStyle.Primary)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tileGap = 12.dp
            val threeAcross = maxWidth >= 190.dp * 3 + tileGap * 2
            if (threeAcross) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(tileGap)) {
                    lead.stages.forEachIndexed { i, stage ->
                        LeadStageTile(i + 1, stage.title, stage.detail, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(tileGap)) {
                    lead.stages.forEachIndexed { i, stage -> LeadStageTile(i + 1, stage.title, stage.detail, Modifier.fillMaxWidth()) }
                }
            }
        }
        lead.notice?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = BauColors.BlueDeep) }
    }
}

@Composable
private fun LeadStageTile(number: Int, title: String, detail: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(BauColors.Paper).padding(16.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            number.toString().padStart(2, '0'), fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.Bold, color = BauColors.BlueDeep,
        )
        Text(sentenceCase(title), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
        Text(detail, fontSize = 13.sp, lineHeight = 19.sp, color = BauColors.TextSecondary)
    }
}

/** "In-Person Stage" → "In-person stage" (the portal uses sentence case; mobile uses title case). */
internal fun sentenceCase(title: String): String =
    title.lowercase().replaceFirstChar { it.uppercaseChar() }

@Composable
private fun SignOutBlock(profile: ProfileUiState, onSignOut: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BauDestructiveButton(
            "Sign Out",
            onClick = onSignOut,
            loading = profile.signingOut,
            modifier = Modifier.widthIn(min = 180.dp).pointerHand(),
        )
        profile.signOutError?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.Warning) }
    }
}

/** Dark "BAU BUILD SCORE" card: gauge, "180 / 500", My status and the explainer. */
@Composable
private fun ScoreCard(score: Int, tier: ScoreTier) {
    val shape = RoundedCornerShape(PortalTokens.CardRadius)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(BauColors.Ink).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonoLabel("BAU BUILD SCORE", color = BauColors.Yellow, modifier = Modifier.align(Alignment.Start))
        ScoreGauge(score, Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(300f / 166f))
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = "Score $score of ${ScoreTier.MAX_SCORE}. My status: ${tier.name}"
            },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    score.toString(), fontFamily = DisplayFamily, fontSize = 40.sp, lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold, color = BauColors.Surface,
                )
                Text(
                    "/ ${ScoreTier.MAX_SCORE}", fontSize = 15.sp, lineHeight = 20.sp, color = PortalTokens.OnDarkMuted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("My status", fontSize = 13.sp, lineHeight = 18.sp, color = PortalTokens.OnDarkMuted)
                Text(tier.name, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = BauColors.Surface)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = PortalTokens.OnDarkLine, thickness = 1.dp)
            Text(
                "Hard projects can ask for a minimum score. Many stay open to everyone, so you can take one on and earn a status raise.",
                modifier = Modifier.padding(top = 12.dp),
                fontSize = 13.sp, lineHeight = 19.sp, color = PortalTokens.OnDarkMuted,
            )
        }
    }
}

@Composable
private fun StatsRow(user: User) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatCard("My earnings", "\$${user.earningsUsd}", Modifier.weight(1f).fillMaxHeight())
        StatCard("Approved projects", user.approvedProjects.toString(), Modifier.weight(1f).fillMaxHeight())
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
