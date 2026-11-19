package com.vesper.baubuild.feature.staff.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.core.model.InquiryLogEntry
import com.vesper.baubuild.core.model.InquiryPriority
import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.feature.staff.inquiries.components.CheckLine
import com.vesper.baubuild.feature.staff.inquiries.components.InquiryStatusChip
import com.vesper.baubuild.feature.staff.inquiries.components.StaffChoiceChip
import com.vesper.baubuild.feature.staff.inquiries.components.StaffColors
import com.vesper.baubuild.feature.staff.inquiries.components.StaffIcons
import com.vesper.baubuild.feature.staff.inquiries.components.StaffInlineError
import com.vesper.baubuild.feature.staff.inquiries.detail.DeadlinePickerDialog
import com.vesper.baubuild.feature.staff.inquiries.detail.IT_CONTACT_MAILTO
import com.vesper.baubuild.feature.staff.inquiries.detail.InquiryDetailUiState
import com.vesper.baubuild.feature.staff.inquiries.detail.InquiryDetailViewModel
import com.vesper.baubuild.feature.staff.inquiries.detail.RefinedBriefCard
import com.vesper.baubuild.feature.staff.inquiries.detail.percentEncode
import com.vesper.baubuild.feature.staff.inquiries.detail.summaryOf

/** Two columns side by side from this content width; stacked below it. */
private val TwoColumnMinWidth = 880.dp

/**
 * Inquiry detail inside the Inquiries page (Web portal design "Portal Staff Detail"): breadcrumb back, header with
 * status / urgent chips and a mono meta line; left = Today (progress, done today, blocker) + Daily log, or the
 * status summary for inquiries that are not in progress; right = the editable Details form.
 *
 * Back (breadcrumb, Esc, system back) asks before dropping unsaved edits. Ctrl/Cmd+S and Enter in a single-line
 * field save.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // ui-backhandler BackHandler, same as the other portal pages.
@Composable
internal fun StaffInquiriesPortalDetail(
    viewModel: InquiryDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestBack = {
        if (state.editor.isDirty && state.canEdit) confirmDiscard = true else onBack()
    }
    BackHandler(onBack = requestBack)

    Column(
        modifier.fillMaxSize().background(BauColors.Paper)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.S && (e.isCtrlPressed || e.isMetaPressed)) {
                    viewModel.save(); true
                } else false
            }
            // Esc also works while focus is inside the page (BackHandler covers the unfocused case).
            .onKeyEvent { e ->
                if (e.key == Key.Escape && e.type == KeyEventType.KeyUp) { requestBack(); true } else false
            },
    ) {
        StaffPortalHeader(title = "Inquiry", refreshing = state.isRefreshing, onRefresh = { viewModel.refresh() })
        Breadcrumb(title = state.data?.title, onBack = requestBack)
        LoadStateContent(state.inquiry, onRetry = { viewModel.refresh() }, modifier = Modifier.fillMaxWidth().weight(1f)) { inquiry ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = StaffPortalGutter, end = StaffPortalGutter, top = 10.dp, bottom = StaffPortalGutter),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DetailHeader(inquiry)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= TwoColumnMinWidth) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(20.dp)) { StatusColumn(inquiry, state, viewModel) }
                            DetailsCard(state, viewModel, Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            StatusColumn(inquiry, state, viewModel)
                            DetailsCard(state, viewModel, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    if (state.showCancelConfirm) CancelDialog(state, viewModel)
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("You edited the details but didn’t save them.") },
            confirmButton = {
                StaffPortalButton("Discard", onClick = { confirmDiscard = false; onBack() }, style = StaffPortalButtonStyle.Danger)
            },
            dismissButton = {
                StaffPortalButton("Keep editing", onClick = { confirmDiscard = false }, style = StaffPortalButtonStyle.Outline)
            },
            containerColor = BauColors.Surface,
        )
    }
}

@Composable
private fun Breadcrumb(title: String?, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = StaffPortalGutter).semantics { contentDescription = "Breadcrumb" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StaffPortalLink("Inquiries", onClick = onBack)
        Text("/", style = TextStyle(fontSize = 14.sp), color = BauColors.TextSecondary)
        Text(
            title ?: "…", style = TextStyle(fontSize = 14.sp), color = BauColors.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeader(inquiry: StaffInquiry) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                inquiry.title, modifier = Modifier.semantics { heading() },
                style = TextStyle(fontFamily = DisplayFamily, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, letterSpacing = (-0.015).em),
                color = BauColors.Ink,
            )
            InquiryStatusChip(inquiry.status)
            if (inquiry.isUrgent) UrgentChip()
        }
        val meta = inquiry.portalDetailMeta
        if (meta.isNotBlank()) {
            Text(meta, style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Left column: Today + Daily log, or the status summary
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun StatusColumn(inquiry: StaffInquiry, state: InquiryDetailUiState, viewModel: InquiryDetailViewModel) {
    val progress = inquiry.progress
    if (progress != null && inquiry.status == InquiryStatus.InProgress) {
        TodayCard(inquiry, state, viewModel)
    } else {
        SummaryCard(inquiry, showRefined = state.showRefined, onToggleRefined = viewModel::toggleRefined)
        val refined = inquiry.refined
        if (state.showRefined && refined != null) RefinedBriefCard(inquiry, refined)
    }
    val log = progress?.log.orEmpty()
    if (log.isNotEmpty()) DailyLogCard(log)
}

private val CardTitle = TextStyle(fontFamily = DisplayFamily, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayCard(inquiry: StaffInquiry, state: InquiryDetailUiState, viewModel: InquiryDetailViewModel) {
    val progress = inquiry.progress ?: return
    Column(Modifier.fillMaxWidth().staffPortalCard().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(progress.today, modifier = Modifier.semantics { heading() }, style = CardTitle, color = BauColors.Ink)
            inquiry.portalDayLine()?.let { Text(it, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = BauColors.TextSecondary) }
        }
        val pct = progress.percentDone.coerceIn(0, 100)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(StaffPortalColors.RowLine)
                    .semantics { contentDescription = "$pct percent done" },
            ) {
                if (pct > 0) Box(Modifier.fillMaxWidth(pct / 100f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(BauColors.BlueDeep))
            }
            Text("$pct%", style = TextStyle(fontFamily = DisplayFamily, fontSize = 18.sp, fontWeight = FontWeight.Bold), color = BauColors.Ink)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Done today", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = BauColors.TextSecondary)
            if (progress.doneToday.isEmpty()) {
                Text("No updates yet today.", style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp), color = BauColors.TextSecondary)
            } else {
                progress.doneToday.forEach { CheckLine(it) }
            }
        }
        val blocker = state.blocker
        if (blocker != null) {
            BlockerBox(
                text = blocker.text,
                raised = "Raised by ${blocker.raisedBy} · ${blocker.raisedAt}",
                inquiryTitle = inquiry.title,
                resolving = state.isResolving,
                error = state.resolveError,
                onResolve = viewModel::resolveBlocker,
            )
        } else if (progress.blocker?.resolved == true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(StaffIcons.Check, null, tint = BauColors.BlueDeep, modifier = Modifier.size(16.dp))
                Text("Blocker resolved. The team can carry on.", style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = BauColors.TextSecondary)
            }
        }
        if (progress.doneToday.isNotEmpty()) {
            Text("Summarized by AI from the team’s commits and notes.", style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = BauColors.TextSecondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockerBox(text: String, raised: String, inquiryTitle: String, resolving: Boolean, error: String?, onResolve: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(BauColors.WarningSoft).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(StaffIcons.Warning, null, tint = BauColors.WarningInk, modifier = Modifier.size(18.dp))
            Text(
                "Blocker", modifier = Modifier.semantics { heading() },
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp), color = BauColors.WarningInk,
            )
        }
        Text(text, style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp), color = StaffColors.BlockerInk)
        Text(raised, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = BauColors.WarningInk)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StaffPortalButton(
                "Contact IT", style = StaffPortalButtonStyle.Dark,
                onClick = { runCatching { uriHandler.openUri("$IT_CONTACT_MAILTO?subject=${percentEncode("Blocker: $inquiryTitle")}") } },
            )
            StaffPortalButton("Mark resolved", onClick = onResolve, style = StaffPortalButtonStyle.OnWarning, loading = resolving)
        }
        if (error != null) StaffInlineError(error)
    }
}

@Composable
private fun SummaryCard(inquiry: StaffInquiry, showRefined: Boolean, onToggleRefined: () -> Unit) {
    val summary = summaryOf(inquiry)
    val base = Modifier.fillMaxWidth()
    Column(
        (if (summary.warning) base.clip(RoundedCornerShape(12.dp)).background(BauColors.WarningSoft) else base.staffPortalCard())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            summary.title, modifier = Modifier.semantics { heading() }, style = CardTitle,
            color = if (summary.warning) BauColors.WarningInk else BauColors.Ink,
        )
        Text(
            summary.body, style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            color = if (summary.warning) StaffColors.BlockerInk else BauColors.TextSecondary,
        )
        summary.detail?.let {
            Text(it, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp), color = BauColors.Ink)
        }
        if (inquiry.refined != null) {
            Row {
                StaffPortalLink(if (showRefined) "Hide refined brief" else "View refined brief", onClick = onToggleRefined)
            }
        }
    }
}

@Composable
private fun DailyLogCard(log: List<InquiryLogEntry>) {
    Column(Modifier.fillMaxWidth().staffPortalCard().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Daily log", modifier = Modifier.semantics { heading() }, style = CardTitle, color = BauColors.Ink)
        Column {
            log.forEachIndexed { i, entry ->
                val last = i == log.lastIndex
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(BauColors.BlueDeep), contentAlignment = Alignment.Center) {
                            Icon(StaffIcons.Tick, null, tint = BauColors.Surface, modifier = Modifier.size(12.dp))
                        }
                        if (!last) Box(Modifier.weight(1f).width(2.dp).background(StaffPortalColors.RowLine))
                    }
                    Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val blocked = !entry.blockers.equals("No blockers", ignoreCase = true) && entry.blockers.isNotBlank()
                        Text(
                            listOf(entry.date, entry.blockers).filter { it.isNotBlank() }.joinToString(" · "),
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                            color = if (blocked) BauColors.WarningInk else BauColors.Ink,
                        )
                        Text(entry.summary, style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp), color = BauColors.TextSecondary)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Right column: Details form
// ---------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsCard(state: InquiryDetailUiState, viewModel: InquiryDetailViewModel, modifier: Modifier) {
    val form = state.form ?: return
    val enabled = state.canEdit && !state.editor.isSaving
    var pickingDate by remember { mutableStateOf(false) }
    val submit = { if (state.canSave) viewModel.save() }

    Column(modifier.staffPortalCard().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Details", modifier = Modifier.semantics { heading() }, style = CardTitle, color = BauColors.Ink)
            Text(
                if (state.canEdit) "Edit and save. The team sees changes right away." else "This inquiry is closed, so its details are read-only.",
                style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp), color = BauColors.TextSecondary,
            )
        }
        StaffPortalField("Project title", form.title, viewModel::setTitle, Modifier.fillMaxWidth(), enabled = enabled, onSubmit = submit)
        StaffPortalField(
            "Project explanation", form.explanation, viewModel::setExplanation, Modifier.fillMaxWidth(),
            enabled = enabled, singleLine = false, minHeight = 110.dp,
        )
        StaffPortalField(
            "What you expect", form.expectation, viewModel::setExpectation, Modifier.fillMaxWidth(),
            enabled = enabled, singleLine = false, minHeight = 90.dp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Project type", modifier = Modifier.weight(1f), style = FieldLabel, color = BauColors.TextSecondary)
                Text("Tick all that apply", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectType.entries.forEach { type ->
                    StaffChoiceChip(
                        type.label, selected = type in form.types, onToggle = { viewModel.toggleType(type) }, enabled = enabled,
                        modifier = if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Priority", style = FieldLabel, color = BauColors.TextSecondary)
            StaffPortalSegmented(
                options = InquiryPriority.entries, selected = form.priority, label = { it.name },
                onSelect = viewModel::setPriority, modifier = Modifier.fillMaxWidth(), fill = true, enabled = enabled,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2,
        ) {
            StaffPortalField(
                "Deadline", form.deadline, viewModel::setDeadline, Modifier.weight(1f),
                enabled = enabled, placeholder = "YYYY-MM-DD", onSubmit = submit,
                trailing = if (enabled) { { StaffPortalLink("Pick", onClick = { pickingDate = true }, fontSize = 14) } } else null,
            )
            StaffPortalField(
                "Reward", form.price, viewModel::setPrice, Modifier.weight(1f),
                enabled = enabled, prefix = "$", placeholder = "0", numeric = true, onSubmit = submit,
            )
        }
        state.editor.saveError?.let { StaffInlineError(it) }
        state.editor.savedMessage?.let { Text(it, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary) }
        if (state.canEdit) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                StaffPortalButton(
                    "Discard changes",
                    onClick = { state.editor.baseline?.let { baseline -> viewModel.editForm { baseline } } },
                    enabled = state.editor.isDirty && !state.editor.isSaving,
                )
                StaffPortalButton(
                    "Save changes", onClick = viewModel::save, style = StaffPortalButtonStyle.Primary,
                    enabled = state.canSave || state.editor.isSaving, loading = state.editor.isSaving,
                )
            }
        }
        if (state.canCancel) {
            HorizontalDivider(thickness = 1.dp, color = StaffPortalColors.RowLine)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Students stop working on it and unspent budget counts as wasted.",
                    modifier = Modifier.weight(1f), style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = BauColors.TextSecondary,
                )
                StaffPortalButton(
                    "Cancel inquiry", onClick = viewModel::requestCancel, style = StaffPortalButtonStyle.Danger,
                    enabled = !state.editor.isSaving, loading = state.isCancelling,
                )
            }
            state.cancelError?.let { StaffInlineError(it) }
        }
    }

    if (pickingDate) {
        DeadlinePickerDialog(initialIso = form.deadline, onPick = viewModel::setDeadline, onDismiss = { pickingDate = false })
    }
}

private val FieldLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)

@Composable
private fun CancelDialog(state: InquiryDetailUiState, viewModel: InquiryDetailViewModel) {
    val budget = state.data?.rewardLabel?.takeIf { it.isNotBlank() && it != "—" }
    AlertDialog(
        onDismissRequest = viewModel::dismissCancel,
        title = { Text("Cancel this inquiry?") },
        text = {
            Text(
                "Students stop working on it" +
                    (if (budget != null) " and the unspent budget ($budget) counts as wasted." else ".") +
                    " This can’t be undone.",
            )
        },
        confirmButton = { StaffPortalButton("Cancel inquiry", onClick = viewModel::confirmCancel, style = StaffPortalButtonStyle.Danger) },
        dismissButton = { StaffPortalButton("Keep it", onClick = viewModel::dismissCancel) },
        containerColor = BauColors.Surface,
    )
}
