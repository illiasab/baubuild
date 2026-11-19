package com.vesper.baubuild.feature.projects

import com.vesper.baubuild.designsystem.tabBarContentPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauPrimaryButton
import com.vesper.baubuild.designsystem.BauSecondaryButton

/** Callbacks of the inquiry form (kept together so the screen signature stays readable). */
internal class InquiryFormActions(
    val onTitle: (String) -> Unit,
    val onDescription: (String) -> Unit,
    val onDepartment: (String) -> Unit,
    val onUrgent: (Boolean) -> Unit,
    val onBounty: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onStartAnother: () -> Unit,
    val onDone: () -> Unit,
)

@Composable
internal fun InquiryFormScreen(
    state: InquiryFormUiState,
    actions: InquiryFormActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical).asPaddingValues()
    val submitted = state.submit as? SubmitState.Submitted
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = insets.calculateTopPadding() + 10.dp)
                // With the pinned submit area below, only a small gap is needed; otherwise clear the tab bar.
                .then(if (submitted == null) Modifier.padding(bottom = 16.dp) else Modifier.tabBarContentPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProjectsTopBar(onBack, backLabel = "Back to Projects")
            if (submitted != null) SubmittedConfirmation(submitted, actions) else FormBody(state, actions)
        }
        if (submitted == null) {
            // Pinned on Paper above the tab bar, or above the keyboard while typing (never both).
            SubmitBar(
                state, actions,
                Modifier.fillMaxWidth().background(BauColors.Paper)
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                    .tabBarContentPadding(),
            )
        }
    }
}

@Composable
private fun FormBody(state: InquiryFormUiState, actions: InquiryFormActions) {
    ProjectsScreenHeader(
        "Request a Project",
        "Tell BAU students what your department needs. Urgent requests appear at the top of Projects.",
    )
    val enabled = !state.isSubmitting
    FormField(
        label = "Title", value = state.title, onValueChange = actions.onTitle, enabled = enabled,
        error = state.titleError.takeIf { state.showErrors }, placeholder = "Shuttle timetable widget",
        capitalization = KeyboardCapitalization.Sentences,
    )
    FormField(
        label = "Description", value = state.description, onValueChange = actions.onDescription, enabled = enabled,
        error = state.descriptionError.takeIf { state.showErrors },
        placeholder = "What should it do, who uses it, and how will you know it works?",
        singleLine = false, capitalization = KeyboardCapitalization.Sentences,
    )
    FormField(
        label = "Department", value = state.department, onValueChange = actions.onDepartment, enabled = enabled,
        error = state.departmentError.takeIf { state.showErrors }, placeholder = "Transport Office",
        capitalization = KeyboardCapitalization.Words,
    )
    FormField(
        label = "Suggested Bounty (optional)", value = state.bounty, onValueChange = actions.onBounty, enabled = enabled,
        error = state.bountyError.takeIf { state.showErrors }, placeholder = "25",
        keyboardType = KeyboardType.Number, prefix = "$", imeAction = ImeAction.Done,
        supporting = "BAU BUILD sets the final bounty.",
    )
    UrgentToggle(state.isUrgent, actions.onUrgent, enabled)
}

@Composable
private fun SubmitBar(state: InquiryFormUiState, actions: InquiryFormActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.submit is SubmitState.Failed) {
            InlineError(state.submit.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else if (state.showErrors && !state.isValid) {
            InlineError("Check the highlighted fields.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        BauPrimaryButton(
            text = if (state.submit is SubmitState.Failed) "Try Again" else "Send Request",
            onClick = actions.onSubmit,
            loading = state.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
        )
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    error: String?,
    placeholder: String,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
    prefix: String? = null,
    supporting: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BauColors.TextSecondary)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            isError = error != null,
            placeholder = { Text(placeholder, color = BauColors.TextTertiary) },
            prefix = prefix?.let { { Text(it, color = BauColors.Ink) } },
            supportingText = (error ?: supporting)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType, imeAction = imeAction),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = BauColors.Surface,
                unfocusedContainerColor = BauColors.Surface,
                disabledContainerColor = BauColors.Surface,
                focusedBorderColor = BauColors.Blue,
                unfocusedBorderColor = BauColors.Line,
                errorBorderColor = BauColors.Warning,
                errorSupportingTextColor = BauColors.WarningInk,
                unfocusedSupportingTextColor = BauColors.TextSecondary,
                focusedSupportingTextColor = BauColors.TextSecondary,
                cursorColor = BauColors.Blue,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun UrgentToggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .projectsCard()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Urgent", fontSize = 17.sp, color = BauColors.Ink)
            Text("Shown at the top of every student’s Projects tab.", fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = null, // the whole row toggles
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = BauColors.Ink, checkedThumbColor = BauColors.Yellow,
                uncheckedTrackColor = BauColors.Line, uncheckedThumbColor = BauColors.Surface, uncheckedBorderColor = BauColors.Line,
            ),
        )
    }
}

@Composable
private fun SubmittedConfirmation(submit: SubmitState.Submitted, actions: InquiryFormActions) {
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(BauColors.Yellow), contentAlignment = Alignment.Center) {
            Icon(ProjectsIcons.Check, contentDescription = null, tint = BauColors.Ink, modifier = Modifier.size(28.dp))
        }
        ProjectsScreenHeader("Request Sent", "Thanks! BAU BUILD will review “${submit.inquiry.title}” and turn it into a project.")
        Column(Modifier.fillMaxWidth().projectsCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(submit.inquiry.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
            Text(
                listOfNotNull(
                    submit.inquiry.department.ifBlank { null },
                    if (submit.inquiry.isUrgent) "Urgent" else null,
                    if (submit.inquiry.bountyUsd > 0) "$${submit.inquiry.bountyUsd} suggested" else null,
                ).joinToString(" · "),
                fontSize = 13.sp, color = BauColors.TextSecondary,
            )
        }
    }
    BauPrimaryButton("Done", onClick = actions.onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp))
    BauSecondaryButton("Request Another", onClick = actions.onStartAnother, modifier = Modifier.fillMaxWidth())
}
