package com.vesper.baubuild.feature.profile.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.feature.profile.edit.DiscardChangesDialog
import com.vesper.baubuild.feature.profile.edit.EditProfileForm
import com.vesper.baubuild.feature.profile.edit.ConnectedGitHub
import com.vesper.baubuild.feature.profile.edit.EditProfileViewModel
import com.vesper.baubuild.feature.profile.edit.SaveBar
import com.vesper.baubuild.feature.review.portal.PortalTokens
import com.vesper.baubuild.feature.review.portal.pointerHand

/** Design width of the desktop Edit Profile dialog. */
private val DialogWidth = 560.dp
private val DialogMaxHeight = 760.dp

/**
 * Desktop (Web portal) Edit Profile: the shared [EditProfileForm] in a ~560dp modal with a scrollable body and the
 * shared [SaveBar] pinned at the bottom. Esc, a click on the backdrop and Cancel all go through
 * [EditProfileViewModel.requestClose], so unsaved edits ask first ([DiscardChangesDialog]). Calls [onClose] once the
 * ViewModel reports `finished` (saved, discarded, or closed clean).
 *
 * Keyboard: Tab / Shift+Tab always move focus (also out of the multi-line bio, which would otherwise insert a tab);
 * Enter never saves or closes — it moves to the next field, adds a skill, or inserts a newline in the bio.
 *
 * [showStudentFields] = false (staff portal) hides Skills, GitHub and Classes: photo, name and bio only.
 */
@Composable
internal fun EditProfileDialog(
    vm: EditProfileViewModel,
    onClose: () -> Unit,
    showStudentFields: Boolean = true,
    connectedGitHub: ConnectedGitHub? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(state.finished) { if (state.finished) currentOnClose() }
    if (state.finished) return

    Dialog(
        onDismissRequest = vm::requestClose,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false),
    ) {
        val focusManager = LocalFocusManager.current
        val shape = RoundedCornerShape(PortalTokens.CardRadius)
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = DialogWidth).fillMaxWidth()
                .heightIn(max = DialogMaxHeight)
                .clip(shape)
                .background(BauColors.Surface)
                .border(1.dp, PortalTokens.CardBorder, shape)
                .onPreviewKeyEvent { e ->
                    val plainTab = e.key == Key.Tab && !e.isCtrlPressed && !e.isMetaPressed && !e.isAltPressed
                    if (!plainTab) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) {
                        focusManager.moveFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                    }
                    true
                },
        ) {
            Text(
                "Edit profile",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp).semantics { heading() },
                fontFamily = DisplayFamily, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink,
            )
            HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)
            EditProfileForm(
                vm,
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
                showStudentFields = showStudentFields,
                connectedGitHub = connectedGitHub,
            )
            HorizontalDivider(color = PortalTokens.Divider, thickness = 1.dp)
            SaveBar(
                state, onSave = vm::save, onCancel = vm::requestClose,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).pointerHand(),
            )
        }
    }

    if (state.showDiscardConfirm) {
        DiscardChangesDialog(onDiscard = vm::discardChanges, onKeepEditing = vm::keepEditing)
    }
}
