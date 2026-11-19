package com.vesper.baubuild.feature.mywork.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.ScreenTitle
import com.vesper.baubuild.feature.mywork.components.BackCircleButton

/**
 * Placeholder for the Mentor AI chat of a project workspace.
 *
 * TODO(mentor-ai): build the chat — needs a backend contract first (e.g. `MentorRepository.send(projectId,
 *  message): Flow<MentorReply>` with streaming), a `MentorViewModel` holding the transcript, and the chat UI
 *  from the design. Keep the user's draft on send failure (zero-downtime rule).
 */
@Composable
internal fun MentorAiScreen(
    @Suppress("UNUSED_PARAMETER") projectId: String, // TODO(mentor-ai): scope the chat to this project.
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = BauSpacing.screen)
            .padding(top = BauSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BauSpacing.lg),
    ) {
        BackCircleButton(contentDescription = "Back to project", onClick = onBack)
        ScreenTitle("Mentor AI", "Ask questions about your tasks and code.", Modifier.semantics { heading() })
        Text(
            "Mentor AI is coming soon. You’ll be able to ask about this project’s tasks, your branch, and review feedback here.",
            style = MaterialTheme.typography.bodyMedium,
            color = BauColors.TextSecondary,
        )
    }
}
