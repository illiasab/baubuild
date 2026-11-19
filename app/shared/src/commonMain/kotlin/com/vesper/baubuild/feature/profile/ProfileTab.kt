package com.vesper.baubuild.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.vesper.baubuild.feature.profile.lead.BecomeLeadScreen
import com.vesper.baubuild.feature.review.TabBarClearance
import com.vesper.baubuild.feature.review.cardShadow

/** Entry point for this feature. Hosted by the Compose tab shell and by the native iOS TabView. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProfileTabRoot(modifier: Modifier = Modifier) {
    val vm = viewModel { ProfileViewModel(AppGraph.current.session) { com.vesper.baubuild.feature.auth.signOut() } }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.showingLead) { vm.closeBecomeLead() }
    if (state.showingLead) {
        BecomeLeadScreen(onClose = vm::closeBecomeLead, modifier = modifier)
    } else {
        ProfileScreen(state, onBecomeLead = vm::openBecomeLead, onSignOut = vm::signOut, modifier = modifier)
    }
}

@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    onBecomeLead: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = 12.dp, bottom = TabBarClearance),
            verticalArrangement = Arrangement.spacedBy(BauSpacing.lg),
        ) {
            ScreenTitle("Profile", modifier = Modifier.semantics { heading() })
            Identity(user)
            ScoreCard(state.score, state.tier)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("My Earnings", "\$${user.earningsUsd}", Modifier.weight(1f))
                StatCard("Approved Projects", user.approvedProjects.toString(), Modifier.weight(1f))
            }
            DetailsCard(
                listOf(
                    "Skills" to user.skills.joinToString().ifBlank { "None yet" },
                    "Classes" to user.classCodes.joinToString().ifBlank { "None yet" },
                    "Role" to user.role.label,
                ),
            )
            if (state.canBecomeLead) {
                BauPrimaryButton("Become a Lead", onClick = onBecomeLead, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp))
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.signingOut) {
                    CircularProgressIndicator(Modifier.padding(BauSpacing.md).size(20.dp), color = BauColors.Ink, strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onSignOut, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Sign Out", style = MaterialTheme.typography.labelLarge, color = BauColors.Warning)
                    }
                }
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
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(BauColors.BlueDeep).clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(user.initials, color = BauColors.Surface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
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

/** Grouped list of label / value rows (Skills, Classes, Role). */
@Composable
private fun DetailsCard(rows: List<Pair<String, String>>) {
    val shape = RoundedCornerShape(BauRadius.lg)
    Column(
        Modifier.fillMaxWidth().cardShadow().clip(shape).background(BauColors.Surface)
            .border(1.dp, BauColors.Line, shape),
    ) {
        rows.forEachIndexed { i, (label, value) ->
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
            if (i < rows.lastIndex) HorizontalDivider(color = BauColors.Line, thickness = 1.dp)
        }
    }
}

internal val Role.label: String get() = when (this) { Role.Student -> "Student"; Role.Lead -> "Lead"; Role.Staff -> "Staff" }
