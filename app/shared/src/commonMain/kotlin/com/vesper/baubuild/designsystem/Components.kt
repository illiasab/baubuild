package com.vesper.baubuild.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vesper.baubuild.core.LoadState

/** Yellow primary CTA ("I'm In", "Sign Up", "Start Proctored Exam"). Min 52dp tall for touch. */
@Composable
fun BauPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(BauRadius.pill),
        colors = ButtonDefaults.buttonColors(
            containerColor = BauColors.Yellow, contentColor = BauColors.Ink,
            disabledContainerColor = BauColors.Line, disabledContentColor = BauColors.TextTertiary,
        ),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = BauColors.Ink, strokeWidth = 2.dp)
        else Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** Dark/outlined secondary action ("Log In", "View Project", "Request"). */
@Composable
fun BauSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(BauRadius.pill),
        border = BorderStroke(1.dp, if (onDark) BauColors.Navy700 else BauColors.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (onDark) Color.White else BauColors.Ink),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** White rounded card with hairline border — the base of every list row in the design. */
@Composable
fun BauCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(BauRadius.lg)
    Column(
        modifier
            .clip(shape)
            .background(BauColors.Surface)
            .border(1.dp, BauColors.Line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(BauSpacing.lg),
        content = content,
    )
}

/** Small status chip: "I'm In", "In Review", "Easy", "Approved", "Changes Requested"... */
@Composable
fun BauChip(text: String, container: Color = BauColors.BlueSoft, content: Color = BauColors.BlueDeep, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(BauRadius.pill))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = content,
    )
}

/** Selectable pill (segmented filters like Easy / My Classes / Hard, My Tasks / My Changes). */
@Composable
fun BauPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(BauRadius.pill)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) BauColors.Ink else Color.Transparent)
            .border(1.dp, if (selected) BauColors.Ink else BauColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = BauSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else BauColors.Ink,
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = BauColors.Blue) }
    }
}

/** Large screen title block ("My Work" + subtitle) used at the top of each tab. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = BauColors.Ink)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = BauColors.TextSecondary)
    }
}

/**
 * Renders a [LoadState] following the zero-downtime rule: content stays visible during refresh and when
 * stale; a quiet banner appears instead of an error screen. Full-screen error only when we never had data.
 */
@Composable
fun <T> LoadStateContent(
    state: LoadState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        LoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BauColors.Ink)
        }
        is LoadState.Failed -> Column(
            modifier.fillMaxSize().padding(BauSpacing.screen),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.message, color = BauColors.TextSecondary)
            Spacer(Modifier.height(BauSpacing.md))
            BauSecondaryButton("Try Again", onRetry)
        }
        is LoadState.Content -> Column(modifier) {
            if (state.stale) StaleBanner(onRetry)
            content(state.data)
        }
    }
}

@Composable
fun StaleBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(BauColors.WarningSoft).clickable(onClick = onRetry)
            .padding(horizontal = BauSpacing.screen, vertical = BauSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Showing saved data. Tap to refresh.", style = MaterialTheme.typography.labelMedium, color = BauColors.WarningInk)
    }
}
