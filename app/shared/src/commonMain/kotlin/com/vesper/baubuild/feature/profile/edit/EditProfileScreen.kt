package com.vesper.baubuild.feature.profile.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.media.rememberImagePicker
import com.vesper.baubuild.designsystem.*
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.review.cardShadow
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Edit Profile — pushed from the mobile Profile tab. Back / Cancel asks before discarding edits; a successful
 * save updates the session user (so Profile and the portal update) and calls [onClose].
 *
 * [instanceKey] must change each time the screen is opened so a fresh form is created (the ViewModel store is
 * the Profile tab's, which outlives this screen).
 *
 * [showStudentFields] = false hides Skills, GitHub and Classes (staff profiles: photo, name and bio only).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EditProfileScreen(
    onClose: () -> Unit,
    instanceKey: Any,
    modifier: Modifier = Modifier,
    showStudentFields: Boolean = true,
    connectedGitHubLogin: String? = null,
    onManageGitHub: () -> Unit = {},
) {
    val vm = rememberEditProfileViewModel(instanceKey)
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = !state.finished) { vm.requestClose() }
    LaunchedEffect(state.finished) { if (state.finished) onClose() }

    Column(
        modifier.fillMaxSize().background(BauColors.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = 12.dp, bottom = BauSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BackButton(onClick = vm::requestClose)
            Text(
                "Edit Profile", modifier = Modifier.semantics { heading() },
                fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.68).sp, color = BauColors.Ink,
            )
            EditProfileForm(
                vm, showStudentFields = showStudentFields,
                connectedGitHub = connectedGitHubLogin?.let { ConnectedGitHub(it, onManageGitHub) },
            )
        }
        // Pinned on Paper 24dp above the tab bar, or above the keyboard while typing (bottomContentPadding takes
        // the higher of the two, so no separate imePadding).
        SaveBar(
            state, onSave = vm::save, onCancel = vm::requestClose,
            modifier = Modifier.background(BauColors.Paper)
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = BauSpacing.md)
                .tabBarContentPadding(),
        )
    }

    if (state.showDiscardConfirm) {
        DiscardChangesDialog(onDiscard = vm::discardChanges, onKeepEditing = vm::keepEditing)
    }
}

/** Creates (or reuses, for the same [key]) the Edit Profile ViewModel. Also used by the desktop portal dialog. */
@Composable
fun rememberEditProfileViewModel(key: Any): EditProfileViewModel =
    viewModel(key = "EditProfile#$key") { EditProfileViewModel(AppGraph.current.session, AppGraph.current.profileRepository) {
        AppGraph.current.gitHubOrNull()?.account?.value?.login
    } }

/**
 * The form fields (photo, name, bio, skills, GitHub, classes) without title, Save or Cancel, so the desktop
 * portal can put it in a dialog. Pair with [SaveBar]-like actions calling [EditProfileViewModel.save] /
 * [EditProfileViewModel.requestClose] and show [DiscardChangesDialog] on `showDiscardConfirm`.
 */
@Composable
fun EditProfileForm(
    vm: EditProfileViewModel,
    modifier: Modifier = Modifier,
    showStudentFields: Boolean = true,
    connectedGitHub: ConnectedGitHub? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberImagePicker(vm::onPhotoPicked)
    EditProfileForm(
        state = state,
        onPickPhoto = picker::launch,
        onRemovePhoto = vm::removePhoto,
        onNameChange = vm::onNameChange,
        onBioChange = vm::onBioChange,
        onSkillDraftChange = vm::onSkillDraftChange,
        onCommitSkill = vm::commitSkillDraft,
        onAddSkill = { vm.addSkill(it) },
        onRemoveSkill = vm::removeSkill,
        onGithubChange = vm::onGithubChange,
        modifier = modifier,
        showStudentFields = showStudentFields,
        connectedGitHub = connectedGitHub,
        onTitleChange = vm::onTitleChange,
        onDepartmentChange = vm::onDepartmentChange,
    )
}

/** GitHub connected with a token (Connect GitHub): Edit Profile shows the login read-only with "Manage". */
data class ConnectedGitHub(val login: String, val onManage: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileForm(
    state: EditProfileUiState,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onNameChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onSkillDraftChange: (String) -> Unit,
    onCommitSkill: () -> Unit,
    onAddSkill: (String) -> Unit,
    onRemoveSkill: (String) -> Unit,
    onGithubChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    showStudentFields: Boolean = true,
    connectedGitHub: ConnectedGitHub? = null,
    onTitleChange: (String) -> Unit = {},
    onDepartmentChange: (String) -> Unit = {},
) {
    val user = state.original ?: return
    val enabled = !state.saving
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PhotoSection(state, enabled, onPickPhoto, onRemovePhoto)

        Field("Display name") {
            OutlinedTextField(
                value = state.displayName, onValueChange = onNameChange, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                isError = state.nameError != null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                shape = FieldShape, colors = bauFieldColors(),
            )
            state.nameError?.let { Hint(it, BauColors.Danger) }
        }

        if (state.isStaff && !showStudentFields) {
            Field("Title") {
                OutlinedTextField(
                    value = state.title, onValueChange = onTitleChange, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Professor, Lecturer, Administrator…", color = BauColors.TextTertiary) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    shape = FieldShape, colors = bauFieldColors(),
                )
            }
            Field("Department") {
                OutlinedTextField(
                    value = state.department, onValueChange = onDepartmentChange, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Computer Engineering", color = BauColors.TextTertiary) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    shape = FieldShape, colors = bauFieldColors(),
                )
            }
        }

        Field("Bio") {
            OutlinedTextField(
                value = state.bio, onValueChange = onBioChange, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                minLines = 3, maxLines = 8,
                placeholder = {
                    Text(
                        if (showStudentFields) "What you study, what you like to build…" else "What you teach, what you’re working on…",
                        color = BauColors.TextTertiary,
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = FieldShape, colors = bauFieldColors(),
            )
            Text(
                "${state.bio.length} / ${EditProfileRules.MAX_BIO}",
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${state.bioRemaining} characters left" },
                textAlign = TextAlign.End, fontSize = 13.sp, lineHeight = 18.sp,
                color = if (state.bioRemaining <= 20) BauColors.Warning else BauColors.TextSecondary,
            )
        }

        if (showStudentFields) StudentFields(state, enabled, user.classCodes, onSkillDraftChange, onCommitSkill, onAddSkill, onRemoveSkill, onGithubChange, connectedGitHub)
    }
}

/** Skills, GitHub and Classes — shown for students only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudentFields(
    state: EditProfileUiState,
    enabled: Boolean,
    classCodes: List<String>,
    onSkillDraftChange: (String) -> Unit,
    onCommitSkill: () -> Unit,
    onAddSkill: (String) -> Unit,
    onRemoveSkill: (String) -> Unit,
    onGithubChange: (String) -> Unit,
    connectedGitHub: ConnectedGitHub?,
) {
    Field("Skills", trailing = "${state.skills.size} / ${EditProfileRules.MAX_SKILLS}") {
        if (state.skills.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.skills.forEach { skill -> SkillChip(skill, enabled, onRemove = { onRemoveSkill(skill) }) }
            }
        }
        if (state.canAddMoreSkills) {
            OutlinedTextField(
                value = state.skillDraft, onValueChange = onSkillDraftChange, enabled = enabled,
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                    // Desktop / hardware keyboards: Enter adds the skill.
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { onCommitSkill(); true } else false
                },
                singleLine = true,
                placeholder = { Text("Add a skill", color = BauColors.TextTertiary) },
                trailingIcon = if (state.skillDraft.isNotBlank()) {
                    { BauTextLink("Add", onClick = onCommitSkill, enabled = enabled, color = BauColors.Blue, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp)) }
                } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommitSkill() }),
                shape = FieldShape, colors = bauFieldColors(),
            )
        }
        state.skillError?.let { Hint(it, BauColors.Warning) }
        val suggestions = state.skillSuggestions
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s -> SuggestionChip(s, enabled, onClick = { onAddSkill(s) }) }
            }
        }
    }

    if (connectedGitHub != null) Field("GitHub") {
        Row(
            Modifier.fillMaxWidth().clip(FieldShape).background(BauColors.Surface).border(1.dp, BauColors.Line, FieldShape)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("@${connectedGitHub.login}", modifier = Modifier.weight(1f), fontSize = 16.sp, color = BauColors.Ink)
            BauTextLink("Manage", onClick = connectedGitHub.onManage, enabled = enabled, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        StatusHint("Connected with a GitHub token", connected = true)
    } else Field("GitHub username") {
        OutlinedTextField(
            value = state.githubUsername, onValueChange = onGithubChange, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            isError = state.githubError != null,
            prefix = { Text("github.com/", color = BauColors.TextTertiary) },
            placeholder = { Text("username", color = BauColors.TextTertiary) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
            ),
            shape = FieldShape, colors = bauFieldColors(),
        )
        when {
            state.githubError != null -> Hint(state.githubError!!, BauColors.Danger)
            state.normalizedGithub.isNotEmpty() -> StatusHint("Connected as @${state.normalizedGithub}", connected = true)
            else -> StatusHint("Not connected — add your username so leads can see your repos.", connected = false)
        }
    }

    Field("Classes") {
        if (classCodes.isEmpty()) {
            Text("None yet", fontSize = 16.sp, color = BauColors.TextSecondary)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                classCodes.forEach { BauChip(it) }
            }
        }
        Hint("Classes come from your BAU enrollment and can’t be edited here.", BauColors.TextSecondary)
    }
}

/** Save (primary) + Cancel, with the inline save error. */
@Composable
fun SaveBar(state: EditProfileUiState, onSave: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.error?.let {
            Text(
                it, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.Danger,
            )
        }
        BauPrimaryButton(
            "Save", onClick = onSave, enabled = state.canSave, loading = state.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        )
        BauTextLink(
            "Cancel", onClick = onCancel, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            color = BauColors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        containerColor = BauColors.Surface,
        title = { Text("Discard changes?", fontWeight = FontWeight.Bold, color = BauColors.Ink) },
        text = { Text("Your edits to your profile haven’t been saved.", color = BauColors.TextSecondary) },
        confirmButton = { BauTextLink("Discard", onClick = onDiscard, color = BauColors.Danger) },
        dismissButton = { BauTextLink("Keep Editing", onClick = onKeepEditing, color = BauColors.Ink, fontWeight = FontWeight.Medium) },
    )
}

// ---- pieces ----

private val FieldShape = RoundedCornerShape(BauRadius.md)

@Composable
private fun bauFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BauColors.Blue,
    unfocusedBorderColor = BauColors.Line,
    disabledBorderColor = BauColors.Line,
    focusedContainerColor = BauColors.Surface,
    unfocusedContainerColor = BauColors.Surface,
    disabledContainerColor = BauColors.Surface,
    errorContainerColor = BauColors.Surface,
    focusedTextColor = BauColors.Ink,
    unfocusedTextColor = BauColors.Ink,
    disabledTextColor = BauColors.TextSecondary,
    cursorColor = BauColors.Blue,
    errorBorderColor = BauColors.Danger,
)

@Composable
private fun Field(label: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink)
            if (trailing != null) Text(trailing, fontSize = 13.sp, color = BauColors.TextSecondary)
        }
        content()
    }
}

@Composable
private fun Hint(text: String, color: Color) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = color)
}

@Composable
private fun StatusHint(text: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) BauColors.BlueDeep else BauColors.TextTertiary))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = if (connected) BauColors.BlueDeep else BauColors.TextSecondary)
    }
}

@Composable
private fun PhotoSection(state: EditProfileUiState, enabled: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    val user = state.original ?: return
    val picked = (state.photo as? PhotoEdit.Picked)?.image
    val pickedBitmap: ImageBitmap? = remember(picked) {
        picked?.let { runCatching { it.bytes.decodeToImageBitmap() }.getOrNull() }
    }
    val shown = user.copy(
        displayName = state.normalizedName.ifBlank { user.displayName },
        avatarUrl = if (state.photo == PhotoEdit.Removed) null else user.avatarUrl,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.clip(CircleShape)
                .clickable(enabled = enabled, onClickLabel = "Choose profile photo", role = Role.Button, onClick = onPick)
                .semantics { contentDescription = if (state.hasPhoto) "Profile photo. Change photo" else "Add profile photo" },
        ) {
            UserAvatar(shown, size = 104.dp, image = pickedBitmap)
            CameraBadge(Modifier.align(Alignment.BottomEnd))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BauTextLink(
                if (state.hasPhoto) "Change Photo" else "Add Photo", onClick = onPick, enabled = enabled,
                color = BauColors.Blue, fontWeight = FontWeight.SemiBold,
            )
            if (state.hasPhoto) {
                BauTextLink("Remove", onClick = onRemove, enabled = enabled, color = BauColors.Danger, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Small yellow "+" badge on the avatar. */
@Composable
private fun CameraBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(32.dp).clip(CircleShape).background(BauColors.Yellow).border(2.dp, BauColors.Paper, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = 2.4f * size.width / 14f
            drawLine(BauColors.Ink, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w, StrokeCap.Round)
            drawLine(BauColors.Ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, StrokeCap.Round)
        }
    }
}

@Composable
private fun SkillChip(skill: String, enabled: Boolean, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(BauRadius.pill)
    Row(
        Modifier.heightIn(min = 40.dp).clip(shape).background(BauColors.BlueSoft).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(skill, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BauColors.BlueDeep)
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .clickable(enabled = enabled, onClickLabel = "Remove $skill", role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove $skill" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(10.dp)) {
                val w = 1.8f * size.width / 10f
                drawLine(BauColors.BlueDeep, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
                drawLine(BauColors.BlueDeep, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SuggestionChip(skill: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(BauRadius.pill)
    Box(
        Modifier.heightIn(min = 40.dp).clip(shape).border(1.dp, BauColors.Line, shape)
            .clickable(enabled = enabled, onClickLabel = "Add $skill", role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ $skill", fontSize = 15.sp, color = BauColors.Ink)
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .clickable(onClickLabel = "Back", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val s = size.width / 20f
            val w = 2.2f * s
            drawLine(BauColors.Ink, Offset(12.5f * s, 4f * s), Offset(6.5f * s, 10f * s), w, StrokeCap.Round)
            drawLine(BauColors.Ink, Offset(6.5f * s, 10f * s), Offset(12.5f * s, 16f * s), w, StrokeCap.Round)
        }
    }
}
