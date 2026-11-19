package com.vesper.baubuild.feature.mywork.mentor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.MonoFamily

/** Colors for [MentorRichText] (light mobile bubbles vs the dark portal panel). */
data class MentorTextColors(
    val text: Color,
    val inlineCode: Color,
    val codeText: Color,
    val codeBackground: Color,
)

/**
 * Mentor reply text: prose with `inline code` in monospace, and ``` fenced blocks as monospace boxes that scroll
 * sideways instead of wrapping. Selectable so students can copy a snippet.
 */
@Composable
fun MentorRichText(text: String, colors: MentorTextColors, style: TextStyle, modifier: Modifier = Modifier) {
    val blocks = remember(text) { mentorBlocks(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MentorBlock.Prose -> Text(
                        buildAnnotatedString {
                            mentorSegments(block.text).forEach { (segment, isCode) ->
                                if (isCode) withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = style.fontSize * 0.93f, color = colors.inlineCode)) { append(segment) }
                                else append(segment)
                            }
                        },
                        style = style,
                        color = colors.text,
                    )
                    is MentorBlock.Code -> Text(
                        block.code,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.codeBackground)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 19.sp),
                        color = colors.codeText,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Plain text for screen readers (no backticks or fences). */
internal fun mentorSpokenText(text: String): String = text.replace("```", " ").replace("`", "")
