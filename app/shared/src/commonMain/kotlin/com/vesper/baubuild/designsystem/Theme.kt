package com.vesper.baubuild.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Design uses SF Pro on iOS (system), Space Grotesk for display, JetBrains Mono for code/branches.
// TODO(design-system): bundle Space Grotesk + JetBrains Mono as composeResources fonts and wire them here.
val DisplayFamily: FontFamily = FontFamily.Default
val MonoFamily: FontFamily = FontFamily.Monospace

private val BauTypography = Typography(
    displaySmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

private val BauScheme = lightColorScheme(
    primary = BauColors.Yellow, onPrimary = BauColors.Ink,
    secondary = BauColors.Blue, onSecondary = BauColors.Surface,
    background = BauColors.Paper, onBackground = BauColors.Ink,
    surface = BauColors.Surface, onSurface = BauColors.Ink,
    onSurfaceVariant = BauColors.TextSecondary, outline = BauColors.Line,
    error = BauColors.Warning,
)

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun BauTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BauScheme, typography = BauTypography) {
        // No gray ripples anywhere: Material components (buttons, cards, switches…) get no ripple, and every
        // clickable uses the press-scale feedback instead. Text links use no indication at all (BauTextLink).
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalRippleConfiguration provides null,
            androidx.compose.foundation.LocalIndication provides PressScaleIndication,
            content = content,
        )
    }
}
