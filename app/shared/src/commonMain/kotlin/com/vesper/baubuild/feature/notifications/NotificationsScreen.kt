package com.vesper.baubuild.feature.notifications

import com.vesper.baubuild.designsystem.bottomContentPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.LoadState
import com.vesper.baubuild.data.repository.AppNotification
import com.vesper.baubuild.data.repository.NotificationType
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.LoadStateContent
import com.vesper.baubuild.feature.projects.ProjectsIcons
import com.vesper.baubuild.feature.projects.ProjectsScreenHeader
import com.vesper.baubuild.feature.projects.ProjectsTopBar
import com.vesper.baubuild.feature.projects.projectsCard

private val Gutter = 20.dp

/** Notification inbox pushed from the Projects home bell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationsScreen(
    state: NotificationsUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpen: (AppNotification) -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Back to Projects",
) {
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical).asPaddingValues()
    val padding = PaddingValues(top = insets.calculateTopPadding() + 10.dp, bottom = bottomContentPadding())
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        when (val load = state.notifications) {
            is LoadState.Content -> LoadStateContent(load, onRetry = onRefresh, modifier = Modifier.fillMaxSize()) { items ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "top") { ProjectsTopBar(onBack, backLabel, Modifier.padding(horizontal = Gutter)) }
                    item(key = "title") { ProjectsScreenHeader("Notifications", null, Modifier.padding(horizontal = Gutter)) }
                    if (items.isEmpty()) {
                        // fillParentMaxHeight keeps the empty state centred while still scrollable for pull-to-refresh.
                        item(key = "empty") { NotificationsEmptyState(Modifier.fillParentMaxHeight(0.7f)) }
                    }
                    items(items, key = { it.id }) { n ->
                        NotificationRow(n, state.now, onClick = { onOpen(n) }, modifier = Modifier.padding(horizontal = Gutter))
                    }
                }
            }
            // Before the first load: keep back + title, spinner / retry below them.
            else -> Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProjectsTopBar(onBack, backLabel, Modifier.padding(horizontal = Gutter))
                ProjectsScreenHeader("Notifications", null, Modifier.padding(horizontal = Gutter))
                Box(Modifier.weight(1f)) { LoadStateContent(load, onRetry = onRefresh) {} }
            }
        }
    }
}

@Composable
internal fun NotificationsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(BauColors.Surface), contentAlignment = Alignment.Center) {
            Icon(ProjectsIcons.Bell, contentDescription = null, tint = BauColors.TextTertiary, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "You have no notifications", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp,
            color = BauColors.Ink, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Join approvals, review updates and urgent requests will show up here.",
            fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NotificationRow(notification: AppNotification, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val unread = !notification.read
    val (icon, container) = notification.type.visual()
    Row(
        modifier.fillMaxWidth()
            .projectsCard(onClick, if (unread) "Mark as read" else null)
            .semantics { if (unread) stateDescription = "Unread" }
            .heightIn(min = 44.dp)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = BauColors.Ink, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    notification.title, modifier = Modifier.weight(1f),
                    fontSize = 16.sp, lineHeight = 21.sp, color = BauColors.Ink,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                )
                Text(relativeTime(notification.createdAtEpochMs, now), fontSize = 13.sp, color = BauColors.TextTertiary)
                if (unread) Box(Modifier.size(8.dp).clip(CircleShape).background(BauColors.Blue))
            }
            if (notification.body.isNotBlank()) {
                Text(
                    notification.body, fontSize = 14.sp, lineHeight = 19.sp,
                    color = if (unread) BauColors.TextSecondary else BauColors.TextTertiary,
                )
            }
        }
    }
}

private fun NotificationType.visual(): Pair<ImageVector, Color> = when (this) {
    NotificationType.JoinApproved -> ProjectsIcons.Check to BauColors.Yellow
    NotificationType.ReviewUpdate -> ProjectsIcons.Review to BauColors.BlueSoft
    NotificationType.UrgentInquiry -> ProjectsIcons.Bolt to BauColors.WarningSoft
    NotificationType.General -> ProjectsIcons.Bell to BauColors.Paper
}
