package com.vesper.baubuild.feature.projects

import com.vesper.baubuild.designsystem.BauTextLink

import com.vesper.baubuild.designsystem.DebugLayout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vesper.baubuild.designsystem.bottomContentPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.core.model.Difficulty
import com.vesper.baubuild.core.model.Inquiry
import com.vesper.baubuild.core.model.Project
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.LoadStateContent

private val Gutter = 20.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectsHomeScreen(
    state: ProjectsHomeUiState,
    onRefresh: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onOpenProjectId: (String) -> Unit,
    onNavigate: (ProjectsRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical).asPaddingValues()
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        val listState = rememberLazyListState()
        if (DebugLayout.startScrolledToBottom) {
            val count = listState.layoutInfo.totalItemsCount
            LaunchedEffect(count) { if (count > 0) listState.scrollToItem(count - 1) }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 10.dp,
                bottom = bottomContentPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "brand") {
                BrandRow(
                    hasUnreadNotifications = state.hasUnreadNotifications,
                    onNotifications = { onNavigate(ProjectsRoute.Notifications) },
                    modifier = Modifier.padding(horizontal = Gutter),
                )
            }
            item(key = "greeting") { Greeting(state.viewer.firstName, Modifier.padding(horizontal = Gutter)) }

            val urgent = (state.urgent as? LoadState.Content)?.data.orEmpty()
            if (urgent.isNotEmpty()) {
                item(key = "urgent") { UrgentRow(urgent, onOpenProjectId) }
            }

            if (state.viewer.canRequestProject) {
                item(key = "request") {
                    RequestProjectCard(
                        onClick = { onNavigate(ProjectsRoute.RequestProject) },
                        modifier = Modifier.padding(horizontal = Gutter),
                    )
                }
            }

            item(key = "browse") { BrowseByDifficulty(onNavigate, Modifier.padding(horizontal = Gutter)) }

            item(key = "open-header") {
                OpenProjectsHeader(
                    onSeeAll = { onNavigate(ProjectsRoute.ProjectList(ProjectListSource.AllOpen)) },
                    modifier = Modifier.padding(horizontal = Gutter),
                )
            }
            item(key = "open-list") {
                LoadStateContent(
                    state = state.projects,
                    onRetry = onRefresh,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                ) { projects ->
                    Column(
                        Modifier.padding(horizontal = Gutter, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (projects.isEmpty()) EmptyNote("No open projects right now. Pull down to check again.")
                        projects.take(ProjectsHomeUiState.HOME_PROJECT_LIMIT).forEach { project ->
                            ProjectRow(project, state.viewer.score, onOpen = { onOpenProject(project) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandRow(hasUnreadNotifications: Boolean, onNotifications: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(Modifier.semantics { contentDescription = "BAU BUILD" })
        Spacer(Modifier.weight(1f))
        NotificationsBellButton(hasUnreadNotifications, onClick = onNotifications)
    }
}

/** The BAU BUILD block mark (28×28 viewBox). */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(30.dp)) {
        val u = size.width / 28f
        val r = CornerRadius(1f * u)
        drawRoundRect(BauColors.Ink, Offset(2 * u, 16 * u), Size(24 * u, 9 * u), r)
        drawRoundRect(BauColors.Ink, Offset(2 * u, 4 * u), Size(11 * u, 9 * u), r)
        drawRoundRect(
            BauColors.Ink, Offset(15.75f * u, 4.75f * u), Size(9.5f * u, 7.5f * u), r,
            style = Stroke(width = 1.5f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f * u, 2f * u))),
        )
    }
}

@Composable
private fun Greeting(firstName: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (firstName != null) Text("Hello, $firstName", fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary)
        Text(
            "Projects", fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 41.sp, letterSpacing = (-0.68).sp,
            color = BauColors.Ink, modifier = Modifier.semantics { heading() },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Urgent inquiries from BAU staff
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun UrgentRow(inquiries: List<Inquiry>, onOpenProjectId: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // One card fills the row; several cards leave a peek of the next one so the row reads as scrollable.
        val full = maxWidth - Gutter * 2
        val cardWidth = if (inquiries.size == 1) full else (full - 28.dp).coerceAtMost(420.dp)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics { contentDescription = "Urgent requests from BAU staff" },
        ) {
            items(inquiries, key = { it.id }) { inquiry ->
                UrgentInquiryCard(
                    inquiry,
                    onOpen = inquiry.projectId?.let { id -> { onOpenProjectId(id) } },
                    modifier = Modifier.width(cardWidth),
                )
            }
        }
    }
}

@Composable
private fun UrgentInquiryCard(inquiry: Inquiry, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .padding(bottom = 12.dp) // room for the shadow inside the LazyRow
            .shadow(14.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.22f), spotColor = BauColors.Ink.copy(alpha = 0.22f))
            .clip(shape)
            .background(BauColors.Ink)
            .then(
                if (onOpen != null) Modifier.clickable(onClickLabel = "View project", role = Role.Button, onClick = onOpen)
                else Modifier,
            )
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(inquiry.urgentEyebrow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, color = BauColors.Yellow)
            Text(
                inquiry.urgentHeadline, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp,
                letterSpacing = (-0.44).sp, color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            if (onOpen != null) {
                Text(
                    "View Project",
                    Modifier.clip(RoundedCornerShape(999.dp)).background(BauColors.Yellow).padding(horizontal = 18.dp, vertical = 9.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink,
                )
            } else {
                Text(
                    "Opening soon · ${inquiry.department}",
                    Modifier.clip(RoundedCornerShape(999.dp)).background(BauColors.Navy700).padding(horizontal = 18.dp, vertical = 9.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                )
            }
        }
        CubeStack()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Staff: Request a Project
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun RequestProjectCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().projectsCard(onClick, "Request a project").padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            ProjectsIcons.Plus, contentDescription = null, tint = BauColors.Ink,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(BauColors.Yellow).padding(10.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Request a Project", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, color = BauColors.Ink)
            Text(
                "Need something built? BAU students can take it on.",
                fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.TextSecondary,
            )
        }
        Icon(ProjectsIcons.ChevronRight, contentDescription = null, tint = BauColors.TextTertiary, modifier = Modifier.size(8.dp, 14.dp))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Browse by Difficulty
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun BrowseByDifficulty(onNavigate: (ProjectsRoute) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Browse by Difficulty", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp,
            color = BauColors.TextSecondary, modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowseTile(
                "Easy", ProjectsIcons.Star, BauColors.Yellow, bordered = false,
                onClick = { onNavigate(ProjectsRoute.ProjectList(ProjectListSource.ByDifficulty(Difficulty.Easy))) },
                modifier = Modifier.weight(1f),
            )
            BrowseTile(
                "My Classes", ProjectsIcons.Book, BauColors.BlueSoft, bordered = false,
                onClick = { onNavigate(ProjectsRoute.MyClasses) },
                modifier = Modifier.weight(1f),
            )
            BrowseTile(
                "Hard", ProjectsIcons.Bolt, BauColors.Surface, bordered = true,
                onClick = { onNavigate(ProjectsRoute.ProjectList(ProjectListSource.ByDifficulty(Difficulty.Hard))) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BrowseTile(
    label: String,
    icon: ImageVector,
    container: Color,
    bordered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .height(84.dp)
            .clip(shape)
            .background(container)
            .then(if (bordered) Modifier.border(1.dp, BauColors.Line, shape) else Modifier)
            .clickable(onClickLabel = "Browse $label", role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = BauColors.Ink, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink, maxLines = 1)
    }
}

@Composable
private fun OpenProjectsHeader(onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Open Projects", fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, letterSpacing = (-0.4).sp,
            color = BauColors.Ink, modifier = Modifier.weight(1f).semantics { heading() },
        )
        BauTextLink("See All", onClick = onSeeAll, fontWeight = FontWeight.Medium)
    }
}
