package com.vesper.baubuild.feature.mywork.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauChip
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSpacing

/** Chip palettes used in My Work / workspace (from the iOS design). */
internal object WorkChipColors {
    val ToDoBorder = Color(0xFFC5CAD3)
}

/** Grouped inset list: white, 24dp radius, hairline border, soft shadow, rows separated by dividers. */
@Composable
internal fun GroupedSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BauSpacing.sm)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = BauSpacing.lg),
            style = MaterialTheme.typography.labelMedium,
            color = BauColors.TextSecondary,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(elevation = 6.dp, shape = shape, ambientColor = BauColors.Ink.copy(alpha = 0.06f), spotColor = BauColors.Ink.copy(alpha = 0.06f))
                .clip(shape)
                .background(BauColors.Surface)
                .border(1.dp, BauColors.Line, shape),
            content = content,
        )
    }
}

/** One row of a [GroupedSection]: title + subtitle, trailing chip, optional chevron when tappable. */
@Composable
internal fun GroupedRow(
    title: String,
    subtitle: String,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    subtitleFontFamily: FontFamily? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = 72.dp)
                .padding(horizontal = BauSpacing.lg, vertical = BauSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, letterSpacing = (-0.02).em),
                    color = BauColors.Ink,
                )
                Text(
                    subtitle,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = subtitleFontFamily),
                    color = BauColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing()
            if (onClick != null) Chevron()
        }
        if (showDivider) HorizontalDivider(thickness = 1.dp, color = BauColors.Line)
    }
}

/** Status chip with the design's exact padding / weight. Wraps [BauChip] colors. */
@Composable
internal fun WorkChip(text: String, container: Color, content: Color, border: Color? = null) {
    val shape = RoundedCornerShape(999.dp)
    BauChip(
        text = text,
        container = container,
        content = content,
        modifier = if (border != null) Modifier.border(1.dp, border, shape) else Modifier,
    )
}

@Composable
internal fun ImInChip() = WorkChip("I’m In", BauColors.Yellow, BauColors.Ink)

@Composable
internal fun Chevron(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 8.dp, height = 14.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.125f, h * (1f / 14f))
            lineTo(w * 0.875f, h * 0.5f)
            lineTo(w * 0.125f, h * (13f / 14f))
        }
        drawPath(path, BauColors.TextTertiary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 44dp glass-style circular back button ("Back to My Work"). */
@Composable
internal fun BackCircleButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .size(44.dp)
            .shadow(8.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.14f), spotColor = BauColors.Ink.copy(alpha = 0.14f))
            .clip(shape)
            .background(Color.White.copy(alpha = 0.85f))
            .border(1.dp, Color.White, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val s = size.width / 20f
            val path = Path().apply {
                moveTo(12f * s, 4f * s)
                lineTo(6f * s, 10f * s)
                lineTo(12f * s, 16f * s)
            }
            drawPath(path, BauColors.Ink, style = Stroke(width = 2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Glass pill button with a speech-bubble icon (Mentor AI entry). */
@Composable
internal fun GlassPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .height(44.dp)
            .shadow(8.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.14f), spotColor = BauColors.Ink.copy(alpha = 0.14f))
            .clip(shape)
            .background(Color.White.copy(alpha = 0.85f))
            .border(1.dp, Color.White, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = BauSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BauSpacing.sm),
    ) {
        Canvas(Modifier.size(18.dp)) {
            val s = size.width / 20f
            val path = Path().apply {
                moveTo(4f * s, 4f * s); lineTo(16f * s, 4f * s); lineTo(16f * s, 13f * s)
                lineTo(9f * s, 13f * s); lineTo(5f * s, 16f * s); lineTo(5f * s, 13f * s)
                lineTo(4f * s, 13f * s); close()
            }
            drawPath(path, BauColors.Ink, style = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = BauColors.Ink)
    }
}

/** Quiet centered message used for empty lists. */
@Composable
internal fun EmptyMessage(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = BauSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BauSpacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = BauColors.Ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = BauColors.TextSecondary)
    }
}
