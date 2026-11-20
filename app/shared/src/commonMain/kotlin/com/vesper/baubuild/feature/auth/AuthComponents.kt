package com.vesper.baubuild.feature.auth

import com.vesper.baubuild.designsystem.BauTextLink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSpacing

/**
 * Layout shared by Log In, Sign Up and Forgot Password: a navy header with the brand mark and a back button
 * (the welcome hero, shortened), then a scrolling paper body. Handles safe-area insets and the keyboard.
 */
@Composable
internal fun AuthSubScreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Welcome-style header: sea-wave bottom edge and the brand blocks animation (used by Log In). */
    hero: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(BauColors.Paper), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            AuthHeader(title, subtitle, onBack, hero)
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = 28.dp)
                    .padding(top = BauSpacing.xxl, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(BauSpacing.lg),
                content = content,
            )
        }
    }
}

@Composable
private fun AuthHeader(title: String, subtitle: String, onBack: () -> Unit, hero: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (hero) Modifier.animatedWaveBackground(BauColors.Ink)
                else Modifier.background(BauColors.Ink, RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp)),
            ),
    ) {
        if (hero) {
            BrandBlocksAnimation(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 34.dp)
                    .height(150.dp)
                    .aspectRatio(280f / 400f),
            )
        }
        AuthHeaderContent(title, subtitle, onBack, hero)
    }
}

@Composable
private fun AuthHeaderContent(title: String, subtitle: String, onBack: () -> Unit, hero: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 28.dp, top = 8.dp, bottom = if (hero) 52.dp else 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackChevronButton(onBack)
            Spacer(Modifier.weight(1f))
            BauBuildMark(Modifier.size(32.dp))
        }
        Column(
            Modifier.padding(start = 12.dp, top = 20.dp, end = if (hero) 96.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).em, color = Color.White,
            )
            Text(subtitle, fontSize = 16.sp, lineHeight = 22.sp, color = BauColors.BlueMist)
        }
    }
}

/** 48dp circular back button with a drawn chevron (no icon font in the project). */
@Composable
private fun BackChevronButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(BauColors.Navy800)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.65f, size.height * 0.15f)
                lineTo(size.width * 0.3f, size.height * 0.5f)
                lineTo(size.width * 0.65f, size.height * 0.85f)
            }
            drawPath(path, Color.White, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The design's 58dp yellow pill with soft drop shadow. */
@Composable
internal fun AuthPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingDescription: String = "Signing in",
) {
    val shape = RoundedCornerShape(29.dp)
    val active = enabled && !loading
    Button(
        onClick = onClick,
        enabled = active,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .then(
                if (active || loading) Modifier.shadow(12.dp, shape, ambientColor = BauColors.Ink.copy(alpha = 0.16f), spotColor = BauColors.Ink.copy(alpha = 0.16f))
                else Modifier
            )
            .semantics { if (loading) contentDescription = loadingDescription },
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = BauColors.Yellow, contentColor = BauColors.Ink,
            disabledContainerColor = if (loading) BauColors.YellowLight else BauColors.Line,
            disabledContentColor = if (loading) BauColors.Ink else BauColors.TextTertiary,
        ),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = BauColors.Ink, strokeWidth = 2.dp)
        else Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)
    }
}

/** "Already a member? **Log In**" style link. 48dp tall for touch. */
@Composable
internal fun AuthTextAction(lead: String?, action: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Only the action words are tappable; the lead text is plain. No background/ripple (see BauTextLink).
    Row(
        modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (lead != null) Text(lead, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BauColors.Ink)
        BauTextLink(action, onClick = onClick)
    }
}

/** Inline warning under a button (zero-downtime rule: never replace the screen with an error). */
@Composable
internal fun AuthInlineError(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier = modifier.fillMaxWidth()
            .background(BauColors.WarningSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.WarningInk, textAlign = TextAlign.Center,
    )
}

/** Colors for the auth text fields (Log In, Sign Up): white field, blue focus ring. */
@Composable
internal fun authFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = BauColors.Surface,
    unfocusedContainerColor = BauColors.Surface,
    disabledContainerColor = BauColors.Surface,
    focusedBorderColor = BauColors.Blue,
    unfocusedBorderColor = BauColors.Line,
    cursorColor = BauColors.Blue,
    focusedTextColor = BauColors.Ink,
    unfocusedTextColor = BauColors.Ink,
)
