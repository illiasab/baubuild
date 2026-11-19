package com.vesper.baubuild.feature.auth.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.DisplayFamily
import com.vesper.baubuild.designsystem.MonoFamily
import com.vesper.baubuild.feature.auth.BauBuildMark
import com.vesper.baubuild.feature.auth.BrandBlocksAnimation

/** Below this window width the split layout collapses to one column (brand header above the form). */
internal val DesktopAuthSplitBreakpoint = 900.dp

private const val PITCH = "Pick a BAU project, build it with an AI mentor, and get it approved by a dev lead."

private val Steps = listOf(
    Triple("Sign up", "With your BAU email", "@stu.bau.edu or @bau.edu, confirmed with a code."),
    Triple("Pick a project", "Real BAU work, posted daily", "Browse projects and say “I’m In”."),
    Triple("Get it approved", "AI review, human sign-off", "A dev lead reviews and approves it."),
)

/**
 * Signed-out desktop layout in the public website's style: a dark hero panel on the left (brand, headline, pitch,
 * the BrandBlocks animation, the three steps) and the screen's form card centred on paper on the right.
 * Under [DesktopAuthSplitBreakpoint] it collapses to one scrolling column. [scrollState] scrolls the form side;
 * the caller resets it when the screen changes. Only [form] changes between auth screens.
 */
@Composable
internal fun DesktopAuthFrame(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    form: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(BauColors.Paper)) {
        if (maxWidth >= DesktopAuthSplitBreakpoint) {
            val panelWidth = (maxWidth * 0.52f).coerceIn(440.dp, 760.dp)
            val viewport = maxHeight
            Row(Modifier.fillMaxSize()) {
                BrandPanel(Modifier.width(panelWidth).fillMaxHeight())
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewport)
                            .padding(horizontal = 40.dp, vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) { form() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                CompactBrandHeader()
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                    contentAlignment = Alignment.TopCenter,
                ) { form() }
            }
        }
    }
}

@Composable
private fun BrandPanel(modifier: Modifier) {
    BoxWithConstraints(modifier.background(BauColors.Ink)) {
        val wide = maxWidth >= 600.dp
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = if (wide) 56.dp else 40.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BrandRow()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    DesktopEyebrow("Free for BAU students and staff", color = BauColors.Yellow)
                    Headline(if (wide) 60.sp else 46.sp)
                    Text(
                        PITCH,
                        modifier = Modifier.widthIn(max = 460.dp),
                        fontSize = 19.sp,
                        lineHeight = 29.sp,
                        color = WebColors.PanelBody,
                    )
                }
                if (wide) {
                    BrandBlocksAnimation(Modifier.width(190.dp).aspectRatio(280f / 400f))
                }
            }
            StepsStrip()
        }
    }
}

@Composable
private fun CompactBrandHeader() {
    Column(
        Modifier.fillMaxWidth().background(BauColors.Ink).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BrandRow()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Headline(38.sp)
                Text(PITCH, fontSize = 16.sp, lineHeight = 24.sp, color = WebColors.PanelBody)
            }
            BrandBlocksAnimation(Modifier.width(96.dp).aspectRatio(280f / 400f))
        }
    }
}

@Composable
private fun BrandRow() {
    Row(
        Modifier.heightIn(min = 44.dp).semantics(mergeDescendants = true) { contentDescription = "BAU BUILD" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BauBuildMark(Modifier.size(30.dp))
        Text(
            "BAU BUILD",
            color = Color.White,
            fontFamily = DisplayFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = 0.04.em,
        )
    }
}

/** "Build something **real.**" — the website's hero pattern, last word in yellow. */
@Composable
private fun Headline(size: TextUnit) {
    Text(
        buildAnnotatedString {
            append("Build something ")
            withStyle(SpanStyle(color = BauColors.Yellow)) { append("real.") }
        },
        modifier = Modifier.semantics { heading() },
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = size,
        lineHeight = size * 1.04f,
        letterSpacing = (-0.02).em,
        color = Color.White,
    )
}

/** The website hero's bottom strip (hairline rule + three columns), here the three steps of how it works. */
@Composable
private fun StepsStrip() {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(WebColors.PanelRule))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Steps.forEachIndexed { index, (label, title, body) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${(index + 1).toString().padStart(2, '0')} · ${label.uppercase()}",
                        fontFamily = MonoFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.06.em,
                        color = BauColors.Yellow,
                    )
                    Text(
                        title,
                        fontFamily = DisplayFamily,
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = WebColors.PanelMuted)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Web · iOS · Android · macOS · Windows",
            fontFamily = MonoFamily,
            fontSize = 12.sp,
            color = WebColors.PanelMuted,
        )
    }
}
