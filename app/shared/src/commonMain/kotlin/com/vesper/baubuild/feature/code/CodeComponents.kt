package com.vesper.baubuild.feature.code

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.data.github.CommitResult
import com.vesper.baubuild.data.github.RepoEntry
import com.vesper.baubuild.data.github.RepoEntryType
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.feature.code.editor.CodeColors
import com.vesper.baubuild.feature.code.editor.CodeEditor
import com.vesper.baubuild.feature.code.editor.CodeEditorState
import com.vesper.baubuild.feature.code.editor.codeTextStyle
import com.vesper.baubuild.feature.code.editor.rememberCodeEditorState
import androidx.compose.ui.focus.FocusRequester

/** Shared pieces of the code workspace used by the mobile screen and the desktop portal page. */

internal val CodeLabelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

/** Hand cursor on desktop; no-op on touch. */
internal fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/** File tree rows (folders collapsible). [rowHeight] is 44dp on mobile, 32dp on desktop. */
@Composable
internal fun FileTree(
    rows: List<RepoEntry>,
    expanded: Set<String>,
    activePath: String?,
    changedPaths: Set<String>,
    onOpen: (RepoEntry) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 44.dp,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
) {
    LazyColumn(modifier, contentPadding = contentPadding) {
        itemsIndexed(rows, key = { _, e -> e.path }) { _, entry ->
            val isDir = entry.type == RepoEntryType.Directory
            val isOpen = entry.path in expanded
            val active = entry.path == activePath
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = rowHeight)
                    .background(
                        when {
                            active -> BauColors.BlueSoft
                            hovered -> BauColors.Paper
                            else -> Color.Transparent
                        },
                    )
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = { onOpen(entry) })
                    .handCursor()
                    .semantics {
                        if (isDir) stateDescription = if (isOpen) "Expanded" else "Collapsed"
                        selected = active
                    }
                    .padding(start = (12 + entry.depth * 14).dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (isDir) (if (isOpen) "▾" else "▸") else " ",
                    modifier = Modifier.width(12.dp),
                    style = TextStyle(fontSize = 12.sp),
                    color = BauColors.TextSecondary,
                )
                Text(
                    entry.name + if (isDir) "/" else "",
                    modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    color = BauColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isDir && entry.path in changedPaths) ChangedBadge()
            }
        }
    }
}

/** "M" marker for a file with uncommitted changes. */
@Composable
internal fun ChangedBadge() {
    Text(
        "M",
        modifier = Modifier.semantics { contentDescription = "Changed" },
        style = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        color = BauColors.Warning,
    )
}

/** Small dot for "edited since last ⌘S". */
@Composable
internal fun UnsavedDot() {
    Box(Modifier.size(7.dp).background(BauColors.Orange, CircleShape).semantics { contentDescription = "Unsaved" })
}

/** Horizontally scrollable file tabs with unsaved dots and close buttons. */
@Composable
internal fun FileTabs(
    session: TaskCodeSession,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        session.tabs.forEach { path ->
            val active = path == session.activePath
            val draft = session.files[path]
            val shape = RoundedCornerShape(8.dp)
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                Modifier
                    .heightIn(min = if (compact) 34.dp else 40.dp)
                    .clip(shape)
                    .background(if (active) BauColors.Surface else if (hovered) BauColors.Surface.copy(alpha = 0.6f) else Color.Transparent)
                    .border(1.dp, if (active) BauColors.Line else Color.Transparent, shape)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(path) })
                    .handCursor()
                    .semantics { selected = active }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    path.substringAfterLast('/'),
                    style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (active) BauColors.Ink else BauColors.TextSecondary,
                    maxLines = 1,
                )
                if (draft?.isUnsaved == true) UnsavedDot()
                else if (draft?.isChanged == true) ChangedBadge()
                Box(
                    Modifier
                        .size(if (compact) 30.dp else 40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Close ${path.substringAfterLast('/')}") { onClose(path) }
                        .handCursor()
                        .semantics { contentDescription = "Close ${path.substringAfterLast('/')}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", style = TextStyle(fontSize = 16.sp), color = BauColors.TextSecondary)
                }
            }
        }
    }
}

/** Read-only unified diff of [original] → [text]. Computed once per change. */
@Composable
internal fun DiffView(original: String, text: String, modifier: Modifier = Modifier) {
    val lines = remember(original, text) { LineDiff.compute(original, text) }
    val style = remember { codeTextStyle() }
    if (lines.none { it.kind != DiffKind.Unchanged }) {
        Box(modifier.background(CodeColors.Background), contentAlignment = Alignment.Center) {
            Text("No changes in this file.", style = TextStyle(fontSize = 14.sp), color = BauColors.TextSecondary)
        }
        return
    }
    val hScroll = rememberScrollState()
    LazyColumn(modifier.background(CodeColors.Background), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(lines) { _, line ->
            val (bg, ink, sign) = when (line.kind) {
                DiffKind.Added -> Triple(CodeColors.Added, CodeColors.AddedInk, "+")
                DiffKind.Removed -> Triple(CodeColors.Removed, CodeColors.RemovedInk, "−")
                DiffKind.Unchanged -> Triple(Color.Transparent, CodeColors.GutterText, " ")
            }
            Row(Modifier.fillMaxWidth().background(bg)) {
                Text(
                    (line.oldNumber?.toString() ?: "").padStart(4) + " " + (line.newNumber?.toString() ?: "").padStart(4) + " " + sign,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                    style = style.copy(color = ink),
                    softWrap = false,
                )
                Text(
                    line.text.ifEmpty { " " },
                    modifier = Modifier.horizontalScroll(hScroll).padding(end = 24.dp),
                    style = style.copy(color = if (line.kind == DiffKind.Unchanged) CodeColors.Text else ink),
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * The active file's body: loading / error / diff / editor. [editorState] is keyed by path + revision so discarding
 * replaces the text, while typing stays local to the editor.
 */
@Composable
internal fun ActiveFileBody(
    state: CodeUiState,
    onEdit: (path: String, text: String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyHint: String,
    focusRequester: FocusRequester? = null,
    onEditorFocusChanged: (Boolean) -> Unit = {},
    onEditorState: (CodeEditorState?) -> Unit = {},
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
) {
    val path = state.activePath
    val draft = state.activeFile
    when {
        path == null -> {
            SideEffect { onEditorState(null) }
            Box(modifier.background(CodeColors.Background).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(emptyHint, style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp), color = BauColors.TextSecondary)
            }
        }
        draft == null -> {
            SideEffect { onEditorState(null) }
            Box(modifier.background(CodeColors.Background).padding(24.dp), contentAlignment = Alignment.Center) {
                val error = state.fileErrors[path]
                if (error != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, style = TextStyle(fontSize = 14.sp), color = BauColors.WarningInk)
                        BauTextLink("Try again", onClick = { onRetry(path) })
                    }
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp), color = BauColors.Ink, strokeWidth = 2.dp)
                }
            }
        }
        path in state.diffPaths -> {
            SideEffect { onEditorState(null) }
            DiffView(draft.original, draft.text, modifier)
        }
        else -> {
            val editor = rememberCodeEditorState(path, draft.revision, draft.text)
            SideEffect { onEditorState(editor) }
            CodeEditor(
                state = editor,
                path = path,
                onTextChange = { onEdit(path, it) },
                modifier = modifier,
                fontSize = fontSize,
                focusRequester = focusRequester,
                onFocusChanged = onEditorFocusChanged,
            )
        }
    }
}

/** Checkbox row per changed file with +/− counts (commit sheet / desktop changes list). */
@Composable
internal fun ChangedFileRow(
    draft: FileDraft,
    included: Boolean,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
) {
    val (added, removed) = remember(draft.original, draft.text) { LineDiff.stats(LineDiff.compute(draft.original, draft.text)) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = included, role = Role.Checkbox, onValueChange = { onToggle() })
            .handCursor(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = included,
            onCheckedChange = null,
            modifier = Modifier.size(44.dp),
            colors = CheckboxDefaults.colors(checkedColor = BauColors.Ink, uncheckedColor = BauColors.TextTertiary, checkmarkColor = BauColors.Yellow),
        )
        Column(Modifier.weight(1f)) {
            Text(
                draft.path.substringAfterLast('/'),
                style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = BauColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val dir = draft.path.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) {
                Text(dir, style = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp), color = BauColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("+$added", style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = CodeColors.AddedInk)
        Spacer(Modifier.width(6.dp))
        Text("−$removed", style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = CodeColors.RemovedInk)
        if (onOpen != null) BauTextLink("Open", onClick = onOpen, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp).handCursor())
        if (onDiscard != null) BauTextLink("Discard", onClick = onDiscard, fontSize = 13.sp, color = BauColors.Danger, modifier = Modifier.handCursor())
    }
}

/** "Committed abc1234 · View on GitHub". */
@Composable
internal fun CommitBanner(commit: CommitResult, onView: () -> Unit, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CodeColors.Added)
            .padding(start = 14.dp, end = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Committed ${commit.sha.take(7)}",
            modifier = Modifier.weight(1f, fill = false),
            style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = CodeColors.AddedInk,
        )
        if (commit.url.isNotBlank()) {
            Text(" · ", style = TextStyle(fontSize = 13.sp), color = CodeColors.AddedInk)
            BauTextLink("View on GitHub", onClick = onView, fontSize = 13.sp, color = CodeColors.AddedInk, modifier = Modifier.handCursor())
        }
        Spacer(Modifier.weight(1f))
        if (onDismiss != null) BauTextLink("Dismiss", onClick = onDismiss, fontSize = 13.sp, color = BauColors.TextSecondary, fontWeight = FontWeight.Medium)
    }
}

/** Confirmation before throwing away a file's edits. */
@Composable
internal fun DiscardChangesDialog(path: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = BauColors.Surface,
        title = { Text("Discard changes?", fontWeight = FontWeight.Bold, color = BauColors.Ink) },
        text = {
            Text(
                "Your edits to ${path.substringAfterLast('/')} will be lost. This can’t be undone.",
                color = BauColors.TextSecondary,
            )
        },
        confirmButton = { BauTextLink("Discard", onClick = onConfirm, color = BauColors.Danger, modifier = Modifier.handCursor()) },
        dismissButton = { BauTextLink("Keep Editing", onClick = onCancel, modifier = Modifier.handCursor()) },
    )
}

/** Inline error / note text. */
@Composable
internal fun InlineError(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        color = BauColors.WarningInk,
    )
}
