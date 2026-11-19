package com.vesper.baubuild.feature.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.model.ReviewStageState
import com.vesper.baubuild.designsystem.BauColors

/**
 * Vertical stage timeline from the design (Review stages, Become a Lead stepper).
 * 28dp marker, 2dp connector, 14dp gap, 18dp between rows.
 *
 * Connector color: below a Done step it is blue when the next step is also Done, yellow when the next
 * step is the Active one, and a hairline otherwise.
 */
@Composable
fun StageTimeline(
    steps: List<TimelineStep>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        steps.forEachIndexed { index, step ->
            val next = steps.getOrNull(index + 1)
            val connector = when {
                next == null -> null
                step.state == ReviewStageState.Done && next.state == ReviewStageState.Done -> BauColors.BlueDeep
                step.state == ReviewStageState.Done && next.state == ReviewStageState.Active -> BauColors.Yellow
                else -> BauColors.Line
            }
            TimelineRow(step, connector, isLast = next == null)
        }
    }
}

class TimelineStep(
    val title: String,
    val state: ReviewStageState,
    /** Review greys out pending titles; the Lead stepper keeps them ink. */
    val dimTitleWhenPending: Boolean = true,
    val content: @Composable ColumnScope.() -> Unit,
)

@Composable
private fun TimelineRow(step: TimelineStep, connector: Color?, isLast: Boolean) {
    val stateLabel = when (step.state) {
        ReviewStageState.Done -> "done"
        ReviewStageState.Active -> "in progress"
        ReviewStageState.Pending -> "not started"
    }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {
            contentDescription = "${step.title}, $stateLabel"
        },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StageMarker(step.state)
            if (connector != null) {
                Box(Modifier.weight(1f).width(2.dp).background(connector, RoundedCornerShape(1.dp)))
            }
        }
        Column(
            Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                step.title,
                fontSize = 17.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.34).sp,
                color = if (step.state == ReviewStageState.Pending && step.dimTitleWhenPending) BauColors.TextSecondary else BauColors.Ink,
            )
            step.content(this)
        }
    }
}

@Composable
fun StageMarker(state: ReviewStageState, modifier: Modifier = Modifier) {
    val m = modifier.size(28.dp).clearAndSetSemantics { }
    when (state) {
        ReviewStageState.Done -> Box(m.background(BauColors.BlueDeep, CircleShape), contentAlignment = Alignment.Center) {
            CheckGlyph(Modifier.size(14.dp), BauColors.Surface)
        }
        ReviewStageState.Active -> Box(m.background(BauColors.Ink, CircleShape).border(7.dp, BauColors.Yellow, CircleShape))
        ReviewStageState.Pending -> Box(m.border(2.dp, BauColors.TextTertiary, CircleShape))
    }
}

@Composable
internal fun CheckGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.width / 14f
        val p = Path().apply {
            moveTo(2.5f * s, 7.5f * s); lineTo(5.5f * s, 10.5f * s); lineTo(11.5f * s, 4f * s)
        }
        drawPath(p, color, style = Stroke(width = 2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
internal fun WarningGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.width / 18f
        drawLine(color, Offset(9f * s, 4f * s), Offset(9f * s, 10f * s), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
        drawLine(color, Offset(9f * s, 13.5f * s), Offset(9f * s, 14f * s), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
    }
}

@Composable
internal fun ChevronGlyph(modifier: Modifier = Modifier.size(width = 8.dp, height = 14.dp), color: Color = BauColors.TextTertiary) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val sx = size.width / 8f
        val sy = size.height / 14f
        val p = Path().apply { moveTo(1f * sx, 1f * sy); lineTo(7f * sx, 7f * sy); lineTo(1f * sx, 13f * sy) }
        drawPath(p, color, style = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
