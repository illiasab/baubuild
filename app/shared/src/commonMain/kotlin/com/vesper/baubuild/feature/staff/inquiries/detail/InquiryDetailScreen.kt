package com.vesper.baubuild.feature.staff.inquiries.detail

import com.vesper.baubuild.feature.staff.inquiries.cancelConfirmText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.core.model.InquiryLogEntry
import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.InquiryProgress
import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.RefinedInquiry
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.tabBarContentPadding
import com.vesper.baubuild.feature.staff.inquiries.IsoDate
import com.vesper.baubuild.feature.staff.inquiries.components.CheckLine
import com.vesper.baubuild.feature.staff.inquiries.components.InquiryStatusChip
import com.vesper.baubuild.feature.staff.inquiries.components.StaffBackButton
import com.vesper.baubuild.feature.staff.inquiries.components.StaffCardButton
import com.vesper.baubuild.feature.staff.inquiries.components.StaffChip
import com.vesper.baubuild.feature.staff.inquiries.components.StaffChoiceChip
import com.vesper.baubuild.feature.staff.inquiries.components.StaffColors
import com.vesper.baubuild.feature.staff.inquiries.components.StaffGroupedSection
import com.vesper.baubuild.feature.staff.inquiries.components.StaffIcons
import com.vesper.baubuild.feature.staff.inquiries.components.StaffInlineError
import com.vesper.baubuild.feature.staff.inquiries.components.StaffListRow
import com.vesper.baubuild.feature.staff.inquiries.components.StaffMiniSegmented
import com.vesper.baubuild.feature.staff.inquiries.components.StaffSegmentedControl
import com.vesper.baubuild.feature.staff.inquiries.components.StaffTag
import com.vesper.baubuild.feature.staff.inquiries.components.staffCard
import com.vesper.baubuild.feature.staff.inquiries.detailEyebrow
import com.vesper.baubuild.feature.staff.inquiries.detailMeta
import com.vesper.baubuild.feature.staff.inquiries.formatLongDate
import com.vesper.baubuild.feature.staff.inquiries.formatShortDate

/** Placeholder IT help-desk address for "Contact IT" until the backend provides a per-blocker contact. */
const val IT_CONTACT_MAILTO = "mailto:it@bau.edu"

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Inquiry detail (staff, mobile). Pushed from the Inquiries list and from Overview (recent rows / needs-attention).
 * Status tab: daily progress, blocker, log or a status summary. Details tab: editable form with a pinned Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InquiryDetailScreen(
    viewModel: InquiryDetailViewModel,
    backLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        // Top bar stays visible in every load state so back is always reachable.
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = BauSpacing.screen)
                .padding(top = BauSpacing.md)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaffBackButton(backLabel, onBack)
            Spacer(Modifier.weight(1f))
            state.data?.let { InquiryStatusChip(it.status) }
        }
        LoadStateContent(state.inquiry, onRetry = { viewModel.refresh() }, modifier = Modifier.weight(1f).fillMaxWidth()) { inquiry ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                val showSaveBar = state.tab == InquiryDetailTab.Details && state.canEdit
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = BauSpacing.screen)
                            .padding(top = 14.dp)
                            .then(if (showSaveBar) Modifier.padding(bottom = BauSpacing.lg) else Modifier.tabBarContentPadding()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        DetailHeader(inquiry)
                        StaffSegmentedControl(
                            options = InquiryDetailTab.entries,
                            selected = state.tab,
                            label = { it.label },
                            onSelect = viewModel::selectTab,
                        )
                        when (state.tab) {
                            InquiryDetailTab.Status -> StatusTab(inquiry, state, viewModel)
                            InquiryDetailTab.Details -> DetailsTab(inquiry, state, viewModel)
                        }
                    }
                    if (showSaveBar) SaveBar(state, onSave = viewModel::save)
                }
            }
        }
    }

    if (state.showCancelConfirm) {
        val inquiry = state.data
        AlertDialog(
            onDismissRequest = viewModel::dismissCancel,
            title = { Text(if (inquiry?.canDelete == true) "Withdraw this inquiry?" else "Cancel this inquiry?") },
            text = { Text(inquiry?.cancelConfirmText() ?: "This can’t be undone.") },
            confirmButton = { BauTextLink("Cancel Inquiry", onClick = viewModel::confirmCancel, color = BauColors.Danger) },
            dismissButton = { BauTextLink("Keep It", onClick = viewModel::dismissCancel, color = BauColors.Ink) },
            containerColor = BauColors.Surface,
        )
    }
}

@Composable
private fun DetailHeader(inquiry: StaffInquiry) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            inquiry.detailEyebrow,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
            color = BauColors.BlueDeep,
        )
        Text(
            inquiry.title,
            modifier = Modifier.semantics { heading() },
            style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, letterSpacing = (-0.02).em),
            color = BauColors.Ink,
        )
        val meta = inquiry.detailMeta
        if (meta.isNotBlank()) Text(meta, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Status tab
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun StatusTab(inquiry: StaffInquiry, state: InquiryDetailUiState, viewModel: InquiryDetailViewModel) {
    val progress = inquiry.progress
    if (progress != null && inquiry.status == InquiryStatus.InProgress) {
        TodayCard(progress)
        val blocker = state.blocker
        if (blocker != null) {
            BlockerCard(
                text = blocker.text,
                raised = "Raised by ${blocker.raisedBy} · ${blocker.raisedAt}",
                inquiryTitle = inquiry.title,
                resolving = state.isResolving,
                error = state.resolveError,
                onResolve = viewModel::resolveBlocker,
            )
        } else if (progress.blocker?.resolved == true) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(StaffIcons.Check, null, tint = BauColors.BlueDeep, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Blocker resolved. The team can carry on.", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
            }
        }
        if (progress.log.isNotEmpty()) EarlierLog(progress.log)
    } else {
        StatusSummaryCard(inquiry, showRefined = state.showRefined, onToggleRefined = viewModel::toggleRefined)
        val refined = inquiry.refined
        if (state.showRefined && refined != null) RefinedBriefCard(inquiry, refined)
        if (progress != null && progress.log.isNotEmpty()) EarlierLog(progress.log)
    }
}

@Composable
private fun TodayCard(progress: InquiryProgress) {
    Column(
        Modifier.fillMaxWidth().staffCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.today, modifier = Modifier.weight(1f),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, letterSpacing = (-0.02).em),
                color = BauColors.Ink,
            )
            Text("Day ${progress.dayOf} of ${progress.totalDays}", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
        }
        val pct = progress.percentDone.coerceIn(0, 100)
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(BauColors.Line)
                .semantics { contentDescription = "$pct percent done" },
        ) {
            if (pct > 0) Box(Modifier.fillMaxWidth(pct / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(BauColors.BlueDeep))
        }
        Text("$pct% done", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
        if (progress.doneToday.isNotEmpty()) {
            Text("Done Today", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.TextSecondary)
            progress.doneToday.forEach { CheckLine(it) }
            Text(
                "Summarized by AI from the team’s commits and notes.",
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = BauColors.TextSecondary,
            )
        } else {
            Text("No updates yet today.", style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = BauColors.TextSecondary)
        }
    }
}

@Composable
private fun BlockerCard(
    text: String,
    raised: String,
    inquiryTitle: String,
    resolving: Boolean,
    error: String?,
    onResolve: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BauColors.WarningSoft)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(StaffIcons.Warning, null, tint = BauColors.WarningInk, modifier = Modifier.size(18.dp))
            Text(
                "Blocker", modifier = Modifier.semantics { heading() },
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp), color = BauColors.WarningInk,
            )
        }
        Text(text, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = StaffColors.BlockerInk)
        Text(raised, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = BauColors.WarningInk)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StaffCardButton(
                "Contact IT", dark = true, modifier = Modifier.weight(1f),
                onClick = { runCatching { uriHandler.openUri("$IT_CONTACT_MAILTO?subject=${percentEncode("Blocker: $inquiryTitle")}") } },
            )
            StaffCardButton("Mark Resolved", dark = false, loading = resolving, onClick = onResolve, modifier = Modifier.weight(1f))
        }
        if (error != null) StaffInlineError(error)
    }
}

@Composable
private fun EarlierLog(log: List<InquiryLogEntry>) {
    StaffGroupedSection("Earlier") {
        log.forEachIndexed { i, entry ->
            StaffListRow(title = entry.date, subtitle = entry.summary, showDivider = i < log.lastIndex) {
                val clear = entry.blockers.equals("No blockers", ignoreCase = true)
                StaffChip(
                    entry.blockers,
                    container = if (clear) StaffColors.Muted else BauColors.WarningSoft,
                    content = if (clear) BauColors.TextSecondary else BauColors.WarningInk,
                )
            }
        }
    }
}

/** What the Status tab says for inquiries without daily progress. */
internal data class StatusSummary(val title: String, val body: String, val detail: String? = null, val warning: Boolean = false)

internal fun summaryOf(inquiry: StaffInquiry): StatusSummary {
    val latest = inquiry.latest.takeIf { it.isNotBlank() }
    return when (inquiry.status) {
        InquiryStatus.Draft -> StatusSummary(
            "Draft", "Not sent to AI yet. Finish the details, then post it with the + button.",
        )
        InquiryStatus.AiRefining, InquiryStatus.ReadyToPost -> StatusSummary(
            latest ?: "Waiting for your check",
            "AI rewrote your inquiry as a clear project brief. Check it before students see it.",
        )
        InquiryStatus.Posted -> StatusSummary(
            latest ?: "Posted",
            "Students can find it in Projects and ask to join. Daily updates start once a team is approved.",
        )
        InquiryStatus.InProgress -> StatusSummary(
            latest ?: "In progress",
            "Students are building it. Daily updates and blockers show up here.",
        )
        InquiryStatus.Done -> StatusSummary(
            "Delivered",
            "The team delivered it and the work was reviewed" + (inquiry.reviewedBy?.let { " by the $it" } ?: "") + ".",
            detail = latest,
        )
        InquiryStatus.Cancelled -> StatusSummary(
            latest ?: "Cancelled",
            "This inquiry was cancelled. Unspent budget from cancelled inquiries counts as wasted.",
            warning = true,
        )
    }
}

@Composable
private fun StatusSummaryCard(inquiry: StaffInquiry, showRefined: Boolean, onToggleRefined: () -> Unit) {
    val summary = summaryOf(inquiry)
    val base = Modifier.fillMaxWidth()
    Column(
        (if (summary.warning) base.clip(RoundedCornerShape(20.dp)).background(BauColors.WarningSoft) else base.staffCard())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            summary.title,
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, letterSpacing = (-0.02).em),
            color = if (summary.warning) BauColors.WarningInk else BauColors.Ink,
        )
        Text(
            summary.body, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
            color = if (summary.warning) StaffColors.BlockerInk else BauColors.TextSecondary,
        )
        if (summary.detail != null) {
            Text(summary.detail, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.Ink)
        }
        if (inquiry.refined != null) {
            BauTextLink(
                if (showRefined) "Hide AI version" else "Review AI version",
                onClick = onToggleRefined,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Read-only copy of the AI-refined brief (same layout as New Inquiry's "Ready to Post"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RefinedBriefCard(inquiry: StaffInquiry, refined: RefinedInquiry) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.fillMaxWidth().staffCard().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    inquiry.subtitle, modifier = Modifier.weight(1f),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.BlueDeep,
                )
                Text(refined.rewardSummary, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = BauColors.Ink)
            }
            Text(
                refined.title,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, letterSpacing = (-0.02).em),
                color = BauColors.Ink,
            )
            Text(refined.brief, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp), color = StaffColors.BriefText)
            if (refined.doneLooksLike.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Done Looks Like", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.TextSecondary)
                    refined.doneLooksLike.forEach { CheckLine(it) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProjectType.entries.filter { it in refined.types }.forEach { StaffTag(it.label) }
                StaffTag(refined.difficulty.name)
                if (refined.minScore > 0) StaffTag("Score ${refined.minScore}+")
                refined.deadline?.let(::formatShortDate)?.let { StaffTag("Due $it") }
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BauColors.BlueSoft).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(StaffIcons.Sparkle, null, tint = BauColors.Ink, modifier = Modifier.size(18.dp))
                Text("What AI Changed", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp), color = BauColors.Ink)
            }
            Text(refined.whatAiChanged, style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp), color = StaffColors.AiNoteText)
        }
        refined.notifiesOnDone?.let { who ->
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(StaffIcons.Bell, null, tint = BauColors.TextSecondary, modifier = Modifier.size(18.dp))
                Text("When it is done, $who, are notified.", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Details tab
// ---------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsTab(inquiry: StaffInquiry, state: InquiryDetailUiState, viewModel: InquiryDetailViewModel) {
    val form = state.form ?: return
    val enabled = state.canEdit && !state.editor.isSaving
    var pickingDate by remember { mutableStateOf(false) }

    if (!state.canEdit) {
        Text(
            "This inquiry is closed, so its details are read-only.",
            modifier = Modifier.padding(horizontal = 4.dp),
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary,
        )
    }
    LabeledField("Project Title", form.title, viewModel::setTitle, enabled = enabled, singleLine = true)
    LabeledField("Project Explanation", form.explanation, viewModel::setExplanation, enabled = enabled, minHeight = 112.dp)
    LabeledField("What You Expect", form.expectation, viewModel::setExpectation, enabled = enabled, minHeight = 92.dp)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text("Project Type", modifier = Modifier.weight(1f), style = FieldLabelStyle, color = BauColors.TextSecondary)
            Text("Tick all that apply", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProjectType.entries.forEach { type ->
                StaffChoiceChip(type.label, selected = type in form.types, onToggle = { viewModel.toggleType(type) }, enabled = enabled)
            }
        }
    }

    Column(Modifier.fillMaxWidth().staffCard()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Priority", modifier = Modifier.weight(1f), style = RowLabelStyle, color = BauColors.Ink)
            StaffMiniSegmented(
                options = InquiryPriority.entries, selected = form.priority, label = { it.name },
                onSelect = viewModel::setPriority, enabled = enabled,
            )
        }
        HorizontalDivider(thickness = 1.dp, color = BauColors.Line)
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Change deadline") { pickingDate = true }
                .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Deadline", modifier = Modifier.weight(1f), style = RowLabelStyle, color = BauColors.Ink)
            Text(formatLongDate(form.deadline) ?: form.deadline.ifBlank { "None" }, style = RowLabelStyle, color = BauColors.TextSecondary)
            if (enabled) Icon(StaffIcons.Chevron, null, tint = BauColors.TextTertiary, modifier = Modifier.size(width = 8.dp, height = 14.dp))
        }
        HorizontalDivider(thickness = 1.dp, color = BauColors.Line)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Reward", modifier = Modifier.weight(1f), style = RowLabelStyle, color = BauColors.Ink)
            Text("$", style = RowLabelStyle, color = BauColors.TextSecondary)
            BasicTextField(
                value = form.price,
                onValueChange = viewModel::setPrice,
                enabled = enabled,
                singleLine = true,
                textStyle = RowLabelStyle.copy(color = BauColors.TextSecondary, textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(BauColors.Blue),
                modifier = Modifier.widthIn(min = 40.dp, max = 96.dp).semantics { contentDescription = "Reward in dollars" },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        if (form.price.isEmpty()) Text("0", style = RowLabelStyle, color = BauColors.TextTertiary)
                        inner()
                    }
                },
            )
        }
    }

    if (state.canCancel) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.isCancelling) {
                CircularProgressIndicator(Modifier.size(20.dp), color = BauColors.Danger, strokeWidth = 2.dp)
            } else {
                BauTextLink("Cancel inquiry", onClick = viewModel::requestCancel, color = BauColors.Danger, enabled = !state.editor.isSaving)
            }
            state.cancelError?.let { StaffInlineError(it, Modifier.padding(top = 4.dp)) }
        }
    }

    if (pickingDate) {
        DeadlinePickerDialog(
            initialIso = form.deadline,
            onPick = viewModel::setDeadline,
            onDismiss = { pickingDate = false },
        )
    }
}

@Composable
private fun SaveBar(state: InquiryDetailUiState, onSave: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(BauColors.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = BauSpacing.screen)
            .padding(top = BauSpacing.md)
            .tabBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.editor.saveError?.let { StaffInlineError(it) }
        state.editor.savedMessage?.let {
            Text(
                it, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary,
            )
        }
        BauPrimaryButton(
            "Save Changes",
            onClick = onSave,
            enabled = state.canSave,
            loading = state.editor.isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        )
    }
}

private val FieldLabelStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
private val RowLabelStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.02).em)

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    singleLine: Boolean = false,
    minHeight: Dp = 48.dp,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 4.dp), style = FieldLabelStyle, color = BauColors.TextSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, color = if (enabled) BauColors.Ink else BauColors.TextSecondary),
            cursorBrush = SolidColor(BauColors.Blue),
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight)
                .clip(shape).background(BauColors.Surface).border(1.dp, BauColors.Line, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { contentDescription = label },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeadlinePickerDialog(initialIso: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = IsoDate.parse(initialIso)?.epochDay?.times(MILLIS_PER_DAY)
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            BauTextLink("Done", onClick = {
                pickerState.selectedDateMillis?.let { onPick(IsoDate.fromEpochDay(it.floorDiv(MILLIS_PER_DAY)).iso) }
                onDismiss()
            }, modifier = Modifier.padding(end = 12.dp))
        },
        dismissButton = {
            Row {
                BauTextLink("No Deadline", onClick = { onPick(""); onDismiss() }, color = BauColors.TextSecondary)
                Spacer(Modifier.width(8.dp))
                BauTextLink("Cancel", onClick = onDismiss, color = BauColors.Ink)
            }
        },
    ) { DatePicker(state = pickerState) }
}

/** Minimal RFC 3986 percent-encoding for mailto subjects. */
internal fun percentEncode(text: String): String = buildString {
    text.encodeToByteArray().forEach { b ->
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch in "-_.~") append(ch)
        else append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
    }
}

private const val HEX = "0123456789ABCDEF"
