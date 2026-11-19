package com.vesper.baubuild.feature.mywork.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.MonoFamily

private val BubbleShape = RoundedCornerShape(10.dp)
private val BubbleText = TextStyle(fontSize = 14.sp, lineHeight = 1.55.em)

// TODO(mentor-ai): the backend lists GET/POST /v1/projects/{id}/mentor/messages (SSE) but doesn't document the
//  shapes yet; until it's wired the chat starts empty with this intro and replies with PLACEHOLDER_REPLY.
private const val MENTOR_INTRO =
    "Mentor AI is coming soon. You’ll be able to ask about this project’s tasks, your branch, and review feedback here."

/** Dark Mentor AI panel from the portal workspace design. Enter sends, Shift+Enter inserts a newline. */
@Composable
internal fun MentorPanel(
    viewModel: MentorChatViewModel,
    modifier: Modifier = Modifier,
    /** Lets the workspace focus the "Ask the mentor" input (e.g. from the task panel's "Ask Mentor AI"). */
    inputFocusRequester: FocusRequester? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable(viewModel.projectId) { mutableStateOf("") }
    val canSend = !state.isReplying && draft.isNotBlank()
    val submit = { if (viewModel.send(draft)) draft = "" }

    val chatScroll = rememberScrollState()
    LaunchedEffect(state.messages.size, state.isReplying) {
        withFrameNanos { } // wait for the new bubble to be measured
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
            if (state.messages.isEmpty()) {
                Text(MENTOR_INTRO, style = BubbleText, color = PortalColors.MentorMuted)
            }
            state.messages.forEach { MessageBubble(it) }
            if (state.isReplying) {
                Text("Mentor is typing…", style = TextStyle(fontSize = 13.sp), color = PortalColors.MentorMuted)
            }
        }

        Text("Ask the mentor", style = TextStyle(fontSize = 13.sp), color = PortalColors.MentorMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
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
                        if (draft.isEmpty()) Text("I’m stuck on…", style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = BauColors.TextTertiary)
                        inner()
                    }
                },
            )
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .alpha(if (canSend) 1f else 0.6f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(BauColors.Yellow)
                    .clickable(enabled = canSend, role = Role.Button, onClick = submit)
                    .then(if (canSend) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Send", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = BauColors.Ink)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MentorMessage) {
    val text = buildAnnotatedString {
        mentorSegments(message.text).forEach { (segment, isCode) ->
            if (isCode) withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = 13.sp, color = BauColors.BlueMist)) { append(segment) }
            else append(segment)
        }
    }
    when (message.author) {
        MentorAuthor.Mentor -> Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(BubbleShape)
                .background(PortalColors.MentorBubble)
                .border(1.dp, PortalColors.MentorBorder, BubbleShape)
                .padding(14.dp)
                .semantics { contentDescription = "Mentor: ${message.text.replace("`", "")}" },
            style = BubbleText,
            color = PortalColors.MentorText,
        )
        MentorAuthor.Me -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text,
                    modifier = Modifier
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
