package com.vesper.baubuild.feature.mywork.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.data.repository.MentorRole
import com.vesper.baubuild.feature.mywork.mentor.MentorBubble
import com.vesper.baubuild.feature.mywork.mentor.MentorChatViewModel
import com.vesper.baubuild.feature.mywork.mentor.MentorRichText
import com.vesper.baubuild.feature.mywork.mentor.MentorTextColors
import com.vesper.baubuild.feature.mywork.mentor.mentorSpokenText

private val BubbleShape = RoundedCornerShape(10.dp)
private val BubbleText = TextStyle(fontSize = 14.sp, lineHeight = 1.55.em)
private val SmallText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

private const val MENTOR_INTRO = "Ask about your task. The mentor gives hints and questions, not the full solution."

private val MentorColors = MentorTextColors(
    text = PortalColors.MentorText,
    inlineCode = BauColors.BlueMist,
    codeText = PortalColors.MentorText,
    codeBackground = PortalColors.MentorInput,
)

/**
 * Dark Mentor AI panel from the portal workspace design, on the shared [MentorChatViewModel] (same as the mobile
 * Mentor screen). Enter sends, Shift+Enter inserts a newline.
 */
@Composable
internal fun MentorPanel(
    viewModel: MentorChatViewModel,
    modifier: Modifier = Modifier,
    /** Lets the workspace focus the "Ask the mentor" input (e.g. from the task panel's "Ask Mentor AI"). */
    inputFocusRequester: FocusRequester? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canSend = state.canSend
    val submit = { viewModel.send(); Unit }

    val chatScroll = rememberScrollState()
    val last = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, last?.text?.length, last?.status) {
        withFrameNanos { } // wait for the new bubble / text to be measured
        chatScroll.scrollTo(chatScroll.maxValue)
    }

    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(BauColors.Ink).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "MENTOR AI",
            modifier = Modifier.semantics { heading() },
            style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em),
            color = BauColors.Yellow,
        )
        Column(
            Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(chatScroll).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state.isLoadingHistory && state.messages.isEmpty() ->
                    Text("Loading earlier messages…", style = SmallText, color = PortalColors.MentorMuted)
                state.historyError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.historyError.orEmpty(), style = SmallText, color = PortalColors.MentorMuted, modifier = Modifier.weight(1f, fill = false))
                    BauTextLink("Try again", onClick = viewModel::loadHistory, color = BauColors.Yellow, fontSize = 13.sp, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
                }
                state.messages.isEmpty() -> Text(MENTOR_INTRO, style = BubbleText, color = PortalColors.MentorMuted)
            }
            state.messages.forEach { MessageBubble(it, onRetry = viewModel::retry) }
        }

        state.taskContext?.let { context ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Asking about: ${context.title ?: "this task"}",
                    modifier = Modifier.weight(1f, fill = false),
                    style = SmallText,
                    color = PortalColors.MentorMuted,
                    maxLines = 1,
                )
                BauTextLink("Clear", onClick = viewModel::clearTaskContext, color = BauColors.BlueMist, fontSize = 13.sp, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
            }
        }
        Text("Ask the mentor", style = SmallText, color = PortalColors.MentorMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = state.draft,
                onValueChange = viewModel::onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .then(if (inputFocusRequester != null) Modifier.focusRequester(inputFocusRequester) else Modifier)
                    .semantics { contentDescription = "Ask the mentor" }
                    .onPreviewKeyEvent { e ->
                        if ((e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                            if (e.type == KeyEventType.KeyDown) submit()
                            true
                        } else {
                            false
                        }
                    },
                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Color.White),
                cursorBrush = SolidColor(Color.White),
                maxLines = 5,
                decorationBox = { inner ->
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(shape)
                            .background(PortalColors.MentorInput)
                            .border(1.dp, PortalColors.MentorInputBorder, shape)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.draft.isEmpty()) Text("I’m stuck on…", style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = BauColors.TextTertiary)
                        inner()
                    }
                },
            )
            if (state.isStreaming) {
                PanelButton("Stop", enabled = true, onClick = viewModel::stop, container = PortalColors.MentorInputBorder, content = Color.White)
            } else {
                PanelButton("Send", enabled = canSend, onClick = submit, container = BauColors.Yellow, content = BauColors.Ink)
            }
        }
        state.counter?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp),
                color = if (state.draft.length >= state.maxLength) BauColors.Orange else PortalColors.MentorMuted,
            )
        }
    }
}

@Composable
private fun PanelButton(text: String, enabled: Boolean, onClick: () -> Unit, container: Color, content: Color) {
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = content)
    }
}

@Composable
private fun MessageBubble(message: MentorBubble, onRetry: () -> Unit) {
    when (message.role) {
        MentorRole.Assistant -> Column(
            Modifier
                .fillMaxWidth()
                .clip(BubbleShape)
                .background(PortalColors.MentorBubble)
                .border(1.dp, if (message.isError) BauColors.Orange else PortalColors.MentorBorder, BubbleShape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val status = message.status
            if (message.text.isEmpty() && status != null) {
                Text(status, style = SmallText, color = PortalColors.MentorMuted, modifier = Modifier.semantics { contentDescription = "Mentor: $status" })
            } else if (message.isError) {
                Text(message.text, style = BubbleText, color = PortalColors.MentorText)
                if (message.canRetry) {
                    BauTextLink("Try again", onClick = onRetry, color = BauColors.Yellow, fontSize = 14.sp, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
                }
            } else {
                MentorRichText(
                    message.text + if (message.isStreaming) " ▍" else "",
                    MentorColors,
                    BubbleText,
                    Modifier.semantics { contentDescription = "Mentor: ${mentorSpokenText(message.text)}" },
                )
            }
            if (message.citations.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.citations.forEach { c ->
                        Text(
                            c.label,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .border(1.dp, PortalColors.MentorInputBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .semantics { contentDescription = "Source: ${c.title}" },
                            style = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp),
                            color = BauColors.BlueMist,
                        )
                    }
                }
            }
        }
        MentorRole.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.CenterEnd) {
                Text(
                    message.text,
                    modifier = Modifier
                        .alpha(if (message.failed) 0.7f else 1f)
                        .clip(BubbleShape)
                        .background(BauColors.BlueDeep)
                        .padding(14.dp)
                        .semantics { contentDescription = "You: ${message.text}" },
                    style = BubbleText,
                    color = Color.White,
                )
            }
        }
    }
}
