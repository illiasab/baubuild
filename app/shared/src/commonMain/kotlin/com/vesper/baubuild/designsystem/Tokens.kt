package com.vesper.baubuild.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colors from https://baybuild.vercel.app/ (iOS app). Light-first design; Ink is the brand dark. */
object BauColors {
    val Ink = Color(0xFF0D1220)          // primary text, dark surfaces, selected pills
    val Navy900 = Color(0xFF131B36)      // hero gradient / urgent card
    val Navy800 = Color(0xFF1A2444)
    val Navy700 = Color(0xFF27335C)
    val Yellow = Color(0xFFFFD60A)       // primary CTA, brand mark
    val YellowLight = Color(0xFFFFE76B)
    val YellowDeep = Color(0xFFD9B400)
    val Blue = Color(0xFF2B6BFF)         // links, focus, active
    val BlueDeep = Color(0xFF2B50E0)
    val BlueSoft = Color(0xFFDCE4FF)     // "I'm In" chip bg
    val BlueMist = Color(0xFF8FA8FF)
    val Paper = Color(0xFFF3F4EF)        // app background
    val Surface = Color(0xFFFFFFFF)      // cards
    val Line = Color(0xFFE1E4DC)         // card borders / dividers
    val TextSecondary = Color(0xFF4A5468)
    val TextTertiary = Color(0xFF8A93A6)
    val Orange = Color(0xFFFF9F1C)
    val Warning = Color(0xFFB4530A)
    val WarningInk = Color(0xFF8A3D00)
    val WarningSoft = Color(0xFFFFE9D2)
}

object BauSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val screen = 20.dp   // horizontal screen gutter
}

object BauRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val pill = 999.dp
}
