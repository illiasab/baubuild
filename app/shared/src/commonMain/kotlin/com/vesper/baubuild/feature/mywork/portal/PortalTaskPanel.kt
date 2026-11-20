package com.vesper.baubuild.feature.mywork.portal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vesper.baubuild.core.dataOrNull
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.di.AppGraph
import com.vesper.baubuild.feature.auth.rememberPrefersReducedMotion
import com.vesper.baubuild.feature.mywork.task.TaskCodeEffects
import com.vesper.baubuild.feature.mywork.task.TaskDetailActions
import com.vesper.baubuild.feature.mywork.task.TaskDetailContent
import com.vesper.baubuild.feature.mywork.task.TaskDetailUiState
import com.vesper.baubuild.feature.mywork.task.TaskDetailViewModel

private val TaskPanelWidth = 480.dp

/**
 * Right-hand task panel over a dim scrim, same chrome as the Projects portal's project detail panel.
 * Scrim click / close button / system back dismiss it (Esc is routed by [PortalWorkspacePage] so it closes the
 * panel before it leaves the workspace). Each opened task gets a fresh [TaskDetailViewModel], cleared on close.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // ui-backhandler BackHandler, same as the other features.
@Composable
internal fun PortalTaskOverlay(
    projectId: String,
    taskId: String?,
    onClose: () -> Unit,
    onAskMentor: () -> Unit,
    onOpenCode: (taskId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberPrefersReducedMotion()
    // Keep the last task while the exit animation runs.
    var shown by remember { mutableStateOf<String?>(null) }
    if (taskId != null) shown = taskId
    BackHandler(enabled = taskId != null) { onClose() }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(taskId != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(BauColors.Ink.copy(alpha = 0.32f))
                    .clickable(interactionSource = null, indication = null, onClickLabel = "Close task details", onClick = onClose),
            )
        }
        AnimatedVisibility(
            taskId != null,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = if (reducedMotion) fadeIn() else slideInHorizontally { it } + fadeIn(),
            exit = if (reducedMotion) fadeOut() else slideOutHorizontally { it } + fadeOut(),
        ) {
            val id = shown ?: return@AnimatedVisibility
            key(id) {
                ScopedViewModels {
                    val repo = AppGraph.current.workRepository
                    val vm = viewModel { TaskDetailViewModel(repo, projectId, id, github = AppGraph.current.gitHubOrNull()) }
                    val state by vm.state.collectAsStateWithLifecycle()
                    TaskCodeEffects(state, vm, onOpenCode = { onOpenCode(id) })
                    PortalTaskDetailPanel(
                        state = state,
                        onClose = onClose,
                        onRetry = vm::refresh,
                        onToggleCriterion = vm::toggleCriterion,
                        onAdvance = vm::advance,
                        onAskMentor = onAskMentor,
                        onOpenCode = { onOpenCode(id) },
                        modifier = Modifier.widthIn(max = TaskPanelWidth).fillMaxWidth().fillMaxHeight()
                            .shadow(24.dp, RoundedCornerShape(0.dp))
                            .border(1.dp, PortalColors.CardBorder),
                    )
                }
            }
        }
    }
}

/** Pure UI over [TaskDetailUiState]: scrollable task body, actions pinned at the bottom. */
@Composable
internal fun PortalTaskDetailPanel(
    state: TaskDetailUiState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onToggleCriterion: (Int) -> Unit,
    onAdvance: () -> Unit,
    onAskMentor: () -> Unit,
    onOpenCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(BauColors.Paper)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                listOfNotNull("Task", state.detail.dataOrNull?.project?.owner).joinToString(" · ").uppercase(),
                modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.72.sp),
                color = BauColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CloseButton("Close task details", onClose)
        }
        LoadStateContent(state.detail, onRetry = onRetry, modifier = Modifier.weight(1f)) { detail ->
            Column(Modifier.fillMaxSize()) {
                TaskDetailContent(
                    detail = detail,
                    checkedCriteria = state.checkedCriteria,
                    onToggleCriterion = onToggleCriterion,
                    branchName = state.branchName,
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 8.dp).padding(bottom = 16.dp),
                )
                Column(Modifier.fillMaxWidth().background(BauColors.Paper)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(PortalColors.RowLine))
                    TaskDetailActions(
                        state = state,
                        onAdvance = onAdvance,
                        onAskMentor = onAskMentor,
                        onOpenCode = onOpenCode,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                }
            }
        }
    }
}

private val CloseIcon: ImageVector =
    ImageVector.Builder("Close", 20.dp, 20.dp, 20f, 20f).apply {
        addPath(addPathNodes("M5 5l10 10M15 5L5 15"), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
    }.build()

/** 44dp outlined icon button, as in the portal header / project panel. */
@Composable
private fun CloseButton(contentDescription: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(44.dp).clip(shape)
            .background(if (hovered) BauColors.Paper else BauColors.Surface)
            .border(1.dp, if (hovered) PortalColors.HoverBorder else PortalColors.ControlBorder, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = contentDescription, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(CloseIcon, null, tint = BauColors.Ink, modifier = Modifier.size(20.dp))
    }
}

/** Fresh ViewModels for a transient surface (the task panel), cleared when it leaves composition. */
@Composable
private fun ScopedViewModels(content: @Composable () -> Unit) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
