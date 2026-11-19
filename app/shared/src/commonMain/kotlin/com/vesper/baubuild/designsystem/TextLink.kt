package com.vesper.baubuild.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Text-only action ("Log In", "See All", "Forgot password?", "Cancel").
 *
 * App-wide rule: text links never show a background, border or ripple when tapped — only the words are the
 * button, and they dim while pressed (iOS-style). Use this instead of Material `TextButton`.
 * The touch target is kept ≥ 44dp tall without drawing anything around the text.
 */
@Composable
fun BauTextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = BauColors.BlueDeep,
    fontSize: TextUnit = 15.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            modifier = Modifier.alpha(
                when {
                    !enabled -> 0.4f
                    pressed -> 0.45f
                    else -> 1f
                },
            ),
            color = color, fontSize = fontSize, fontWeight = fontWeight,
        )
    }
}
