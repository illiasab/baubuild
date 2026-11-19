package com.vesper.baubuild.feature.mywork.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.baubuild.data.repository.MentorRole
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauRadius
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.designsystem.ScreenTitle
import com.vesper.baubuild.designsystem.tabBarContentPadding
import com.vesper.baubuild.feature.mywork.components.BackCircleButton
import com.vesper.baubuild.feature.mywork.mentor.MentorBubble
import com.vesper.baubuild.feature.mywork.mentor.MentorChatUiState
import com.vesper.baubuild.feature.mywork.mentor.MentorChatViewModel
import com.vesper.baubuild.feature.mywork.mentor.MentorRichText
import com.vesper.baubuild.feature.mywork.mentor.MentorTextColors
import com.vesper.baubuild.feature.mywork.mentor.mentorSpokenText

/** Empty-state suggestions; tapping one sends it. */
internal val MentorSuggestions = listOf("Where should I start?", "Explain this task", "How do I test this?")

private val BubbleShape = RoundedCornerShape(18.dp)
private val BubbleText = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
private val SmallText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

private val LightColors = MentorTextColors(
    text = BauColors.Ink,
    inlineCode = BauColors.BlueDeep,
    codeText = Color(0xFFE6EAF3),
    codeBackground = BauColors.Ink,
)

/**
 * Mentor AI chat of a project workspace (mobile). Same [MentorChatViewModel] as the desktop portal's Mentor panel.
 * The input bar is pinned on Paper above the tab bar, or above the keyboard while typing (`tabBarContentPadding`
 * takes the higher of the two, so no separate imePadding).
 */
@Composable
internal fun MentorAiScreen(
    viewModel: MentorChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val last = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, last?.text?.length, last?.status) {
        if (state.messages.isNotEmpty()) list.scrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1, 100_000)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(BauColors.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            state = list,
            contentPadding = PaddingValues(start = BauSpacing.screen, end = BauSpacing.screen, top = BauSpacing.sm, bottom = BauSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BauSpacing.md),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(BauSpacing.lg), modifier = Modifier.padding(bottom = BauSpacing.sm)) {
                    BackCircleButton(contentDescription = "Back to project", onClick = onBack)
                    ScreenTitle("Mentor AI", "Hints and questions about your tasks, not full solutions.", Modifier.semantics { heading() })
                }
            }
            when {
                state.isLoadingHistory && state.messages.isEmpty() -> item(key = "loading") {
                    Text("Loading earlier messages…", style = SmallText, color = BauColors.TextSecondary)
                }
                state.historyError != null -> item(key = "history-error") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.historyError.orEmpty(), style = SmallText, color = BauColors.TextSecondary, modifier = Modifier.weight(1f, fill = false))
                        BauTextLink("Try again", onClick = viewModel::loadHistory, fontSize = 13.sp)
                    }
                }
            }
            if (state.isEmpty) {
                item(key = "empty") { EmptyState(enabled = !state.isStreaming, onSuggestion = { viewModel.send(it) }) }
            }
            items(state.messages, key = { it.id }) { MessageBubble(it, onRetry = viewModel::retry) }
        }

        InputBar(
            state = state,
            onDraftChange = viewModel::onDraftChange,
            onSend = { viewModel.send() },
            onStop = viewModel::stop,
            onClearContext = viewModel::clearTaskContext,
            modifier = Modifier
                .background(BauColors.Paper)
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, top = BauSpacing.sm)
                .tabBarContentPadding(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(enabled: Boolean, onSuggestion: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = BauSpacing.lg), verticalArrangement = Arrangement.spacedBy(BauSpacing.md)) {
        Text(
            "Ask about your task. The mentor answers with hints and questions so you learn by doing it yourself.",
            style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            color = BauColors.TextSecondary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm), verticalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
            MentorSuggestions.forEach { text ->
                val shape = RoundedCornerShape(BauRadius.pill)
                Box(
                    Modifier
                        .heightIn(min = 44.dp)
                        .clip(shape)
                        .background(BauColors.Surface)
                        .border(1.dp, BauColors.Line, shape)
                        .clickable(enabled = enabled, role = Role.Button) { onSuggestion(text) }
                        .padding(horizontal = BauSpacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = BauColors.Ink)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(message: MentorBubble, onRetry: () -> Unit) {
    when (message.role) {
        MentorRole.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.fillMaxWidth(0.85f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    message.text,
                    modifier = Modifier
                        .alpha(if (message.failed) 0.7f else 1f)
                        .clip(BubbleShape)
                        .background(BauColors.BlueDeep)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics { contentDescription = "You: ${message.text}" },
                    style = BubbleText,
                    color = Color.White,
                )
                if (message.failed) Text("Not answered", style = SmallText, color = BauColors.TextTertiary)
            }
        }
        MentorRole.Assistant -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Column(
                Modifier
                    .fillMaxWidth(0.92f)
                    .clip(BubbleShape)
                    .background(if (message.isError) BauColors.WarningSoft else BauColors.Surface)
                    .border(1.dp, if (message.isError) BauColors.WarningSoft else BauColors.Line, BubbleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(BauSpacing.sm),
            ) {
                val status = message.status
                when {
                    message.text.isEmpty() && status != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
                        TypingDot()
                        Text(status, style = SmallText, color = BauColors.TextSecondary, modifier = Modifier.semantics { contentDescription = "Mentor: $status" })
                    }
                    message.isError -> {
                        Text(message.text, style = BubbleText, color = BauColors.WarningInk)
                        if (message.canRetry) BauTextLink("Try again", onClick = onRetry, color = BauColors.WarningInk)
                    }
                    else -> MentorRichText(
                        message.text + if (message.isStreaming) " ▍" else "",
                        LightColors,
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
                                    .clip(RoundedCornerShape(BauRadius.pill))
                                    .background(BauColors.BlueSoft)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .semantics { contentDescription = "Source: ${c.title}" },
                                style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp),
                                color = BauColors.BlueDeep,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingDot() {
    Box(Modifier.size(8.dp).clip(CircleShape).background(BauColors.Blue))
}

@Composable
private fun InputBar(
    state: MentorChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onClearContext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.taskContext?.let { context ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Asking about: ${context.title ?: "this task"}",
                    modifier = Modifier.weight(1f, fill = false),
                    style = SmallText,
                    color = BauColors.TextSecondary,
                    maxLines = 1,
                )
                BauTextLink("Clear", onClick = onClearContext, fontSize = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
            val shape = RoundedCornerShape(22.dp)
            BasicTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Ask the mentor" },
                textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = BauColors.Ink),
                cursorBrush = SolidColor(BauColors.Blue),
                maxLines = 5,
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(shape)
                            .background(BauColors.Surface)
                            .border(1.dp, BauColors.Line, shape)
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.draft.isEmpty()) Text("Ask the mentor…", style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp), color = BauColors.TextTertiary)
                        inner()
                    }
                },
            )
            if (state.isStreaming) {
                RoundAction("Stop reply", enabled = true, container = BauColors.Ink, onClick = onStop) {
                    Canvas(Modifier.size(14.dp)) {
                        drawRoundRect(Color.White, topLeft = Offset.Zero, size = Size(size.width, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
                    }
                }
            } else {
                RoundAction("Send", enabled = state.canSend, container = BauColors.Yellow, onClick = onSend) {
                    Canvas(Modifier.size(18.dp)) {
                        val s = size.width / 20f
                        val path = Path().apply {
                            moveTo(10f * s, 16f * s); lineTo(10f * s, 4f * s)
                            moveTo(5f * s, 9f * s); lineTo(10f * s, 4f * s); lineTo(15f * s, 9f * s)
                        }
                        drawPath(path, BauColors.Ink, style = Stroke(width = 2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
        state.counter?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp),
                color = if (state.draft.length >= state.maxLength) BauColors.Warning else BauColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun RoundAction(label: String, enabled: Boolean, container: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon() }
}
