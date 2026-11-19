package com.vesper.baubuild.feature.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.JoinPolicy
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.core.model.Reviewer
import com.vesper.baubuild.designsystem.*
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.review.ProjectSummaryCard
import com.vesper.baubuild.feature.review.TabBarClearance
import com.vesper.baubuild.feature.review.label

/** Entry point for this feature. Hosted by the Compose tab shell and by the native iOS TabView. */
@Composable
fun SearchRoot(modifier: Modifier = Modifier) {
    val vm = viewModel { SearchViewModel(AppGraph.current.projectRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    SearchScreen(
        state,
        onQueryChange = vm::onQueryChange,
        onClear = vm::clearQuery,
        onRecent = vm::useRecent,
        onClearRecent = vm::clearRecent,
        onOpen = vm::open,
        onDismissDetail = vm::dismissDetail,
        onRetry = vm::refresh,
        modifier = modifier,
    )
}

@Composable
internal fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onOpen: (Project) -> Unit,
    onDismissDetail: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(BauColors.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Column(
            Modifier.padding(start = BauSpacing.screen, end = BauSpacing.screen, top = 12.dp, bottom = BauSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BauSpacing.md),
        ) {
            ScreenTitle("Search", modifier = Modifier.semantics { heading() })
            SearchField(state.query, onQueryChange, onClear)
        }
        LoadStateContent(state.projects, onRetry = onRetry, modifier = Modifier.fillMaxSize()) {
            SearchBody(state, onRecent, onClearRecent, onOpen)
        }
    }
    state.selected?.let { ProjectDetailSheet(it, onDismissDetail) }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(BauRadius.pill)
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(focus)
            .border(1.dp, BauColors.Line, shape),
        placeholder = { Text("Search projects by title or owner", color = BauColors.TextTertiary) },
        leadingIcon = { SearchGlyph() },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = onClear, modifier = Modifier.semantics { contentDescription = "Clear search" }) {
                    ClearGlyph()
                }
            }
        } else null,
        singleLine = true,
        shape = shape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = BauColors.Surface, unfocusedContainerColor = BauColors.Surface,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            cursorColor = BauColors.Blue, focusedTextColor = BauColors.Ink, unfocusedTextColor = BauColors.Ink,
        ),
    )
}

@Composable
private fun SearchBody(
    state: SearchUiState,
    onRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onOpen: (Project) -> Unit,
) {
    val all = (state.projects as? LoadState.Content)?.data.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = BauSpacing.screen, end = BauSpacing.screen, top = BauSpacing.xs, bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(BauSpacing.md),
    ) {
        when {
            state.noMatches -> item {
                EmptyMessage(
                    "No projects match “${state.activeQuery}”",
                    "Try a shorter word, a unit like “Library”, or a class code.",
                )
            }
            state.isSearching -> {
                item { Caption(if (state.results.size == 1) "1 project" else "${state.results.size} projects") }
                items(state.results, key = { it.id }) { p -> ProjectSummaryCard(p, caption = null, onClick = { onOpen(p) }) }
            }
            else -> {
                if (state.recent.isNotEmpty()) {
                    item { SectionHeader("Recent", action = "Clear", onAction = onClearRecent) }
                    items(state.recent, key = { "recent:$it" }) { q -> RecentRow(q) { onRecent(q) } }
                    item { Spacer(Modifier.height(BauSpacing.sm)) }
                }
                if (all.isEmpty()) {
                    item { EmptyMessage("No open projects right now", "New projects show up here as soon as they open.") }
                } else {
                    item { SectionHeader("Open projects") }
                    items(all, key = { it.id }) { p -> ProjectSummaryCard(p, caption = null, onClick = { onOpen(p) }) }
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = BauColors.TextSecondary)
}

@Composable
private fun EmptyMessage(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = BauSpacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = BauColors.Ink)
        Spacer(Modifier.height(BauSpacing.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = BauColors.TextSecondary)
    }
}

@Composable
private fun RecentRow(query: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(BauRadius.md))
            .clickable(onClickLabel = "Search for $query", role = Role.Button, onClick = onClick)
            .padding(horizontal = BauSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BauSpacing.md),
    ) {
        SearchGlyph(color = BauColors.TextTertiary)
        Text(query, style = MaterialTheme.typography.bodyLarge, color = BauColors.Ink)
    }
}

/** Feature-local project detail. The full project screen (join etc.) belongs to the Projects feature. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDetailSheet(project: Project, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BauColors.Surface) {
        Column(
            Modifier.fillMaxWidth().padding(start = BauSpacing.screen, end = BauSpacing.screen, bottom = BauSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(BauSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(BauSpacing.md)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        project.title, modifier = Modifier.semantics { heading() },
                        fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink,
                    )
                    Text("${project.owner} · ${project.difficulty.label}", fontSize = 15.sp, color = BauColors.TextSecondary)
                }
                Text("\$${project.bountyUsd}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
                BauChip(if (project.joinPolicy == JoinPolicy.Open) "Everyone welcome" else "Needs approval")
                BauChip(
                    if (project.dueInDays == 1) "Due in 1 day" else "Due in ${project.dueInDays} days",
                    container = BauColors.Paper, content = BauColors.Ink,
                )
            }
            Text(project.description, style = MaterialTheme.typography.bodyLarge, color = BauColors.Ink)
            DetailRow("Minimum score", if (project.minScore == 0) "Any score" else project.minScore.toString())
            DetailRow("Reviewed by", project.reviewer.label)
            project.repoName?.let { DetailRow("Repository", it, mono = true) }
            if (project.taskCount > 0) DetailRow("Tasks", project.taskCount.toString())
            Spacer(Modifier.height(BauSpacing.xs))
            Text(
                "Open the Projects tab to join this project.",
                style = MaterialTheme.typography.labelMedium, color = BauColors.TextSecondary,
            )
            BauSecondaryButton("Close", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BauColors.TextSecondary)
        Text(
            value, style = MaterialTheme.typography.bodyMedium, color = BauColors.Ink,
            fontFamily = if (mono) MonoFamily else null,
        )
    }
}

private val Reviewer.label: String
    get() = when (this) {
        Reviewer.AiThenDevLead -> "AI, then a dev lead"
        Reviewer.Professor -> "Professor"
        Reviewer.DevLead -> "Dev lead"
    }

@Composable
private fun SearchGlyph(color: Color = BauColors.Ink) {
    Canvas(Modifier.size(20.dp).clearAndSetSemantics { }) {
        val s = size.width / 24f
        drawCircle(color, radius = 6.5f * s, center = Offset(10.5f * s, 10.5f * s), style = Stroke(2f * s))
        drawLine(color, Offset(15.5f * s, 15.5f * s), Offset(20f * s, 20f * s), 2f * s, StrokeCap.Round)
    }
}

@Composable
private fun ClearGlyph() {
    Box(Modifier.size(20.dp).clip(CircleShape).background(BauColors.TextTertiary), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(8.dp)) {
            val w = 1.6.dp.toPx()
            drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
            drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
        }
    }
}
