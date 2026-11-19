package com.vesper.baubuild.feature.staff.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vesper.baubuild.core.media.LocalImageStore
import com.vesper.baubuild.core.model.SeniorEngineer
import com.vesper.baubuild.designsystem.BauChip
import com.vesper.baubuild.designsystem.BauColors
import com.vesper.baubuild.designsystem.BauSpacing
import com.vesper.baubuild.designsystem.BauTextLink
import com.vesper.baubuild.designsystem.DisplayFamily
import org.jetbrains.compose.resources.decodeToImageBitmap

// Senior engineer list rows + bio, shared by the mobile Profile (bottom sheet) and the desktop portal (dialog).

/** Round initials avatar (photo when we already hold its bytes), same look as `UserAvatar`. Decorative. */
@Composable
internal fun SeniorEngineerAvatar(engineer: SeniorEngineer, size: Dp, modifier: Modifier = Modifier) {
    val photo: ImageBitmap? = remember(engineer.avatarUrl) {
        engineer.avatarUrl?.let(LocalImageStore::get)?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
    }
    val fontSize = with(LocalDensity.current) { (size * 0.375f).toSp() }
    Box(
        modifier.size(size).clip(CircleShape).background(BauColors.BlueDeep).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(photo, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Text(engineer.initials, color = BauColors.Surface, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Mobile list row: avatar, name, title, chevron. The whole row is the button (global press-scale feedback). */
@Composable
internal fun SeniorEngineerRow(engineer: SeniorEngineer, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Show ${engineer.name}’s bio", onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SeniorEngineerAvatar(engineer, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(engineer.name, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = BauColors.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (engineer.title.isNotBlank()) {
                Text(engineer.title, fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("›", fontSize = 22.sp, color = BauColors.TextTertiary, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** "Notified when your projects are done · Runs Lead interviews". */
internal fun SeniorEngineer.roleNote(): String =
    listOfNotNull("Notified when your projects are done", "Runs Lead interviews".takeIf { isInterviewer }).joinToString(" · ")

internal fun githubUrl(username: String) = "https://github.com/${username.trim().removePrefix("@")}"

/**
 * The bio itself: large avatar, name, title, bio, skills, GitHub / Email links, role note, and a quiet loading /
 * inline error line. Never blank: whatever we already know about the engineer is always shown.
 */
@Composable
internal fun SeniorEngineerBioContent(
    sheet: SeniorEngineerSheet,
    modifier: Modifier = Modifier,
    linkModifier: Modifier = Modifier,
) {
    val e = sheet.engineer
    val uriHandler = LocalUriHandler.current
    val open: (String) -> Unit = { runCatching { uriHandler.openUri(it) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BauSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SeniorEngineerAvatar(e, 72.dp)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    e.name, modifier = Modifier.semantics { heading() },
                    fontFamily = DisplayFamily, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = BauColors.Ink,
                )
                if (e.title.isNotBlank()) Text(e.title, fontSize = 15.sp, lineHeight = 20.sp, color = BauColors.TextSecondary)
            }
            if (sheet.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = BauColors.TextTertiary, strokeWidth = 2.dp)
            }
        }

        if (e.bio.isNotBlank()) Text(e.bio, fontSize = 16.sp, lineHeight = 23.sp, color = BauColors.Ink)

        if (e.skills.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                e.skills.forEach { BauChip(it) }
            }
        }

        if (e.githubUsername != null || e.email != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                e.githubUsername?.let { user ->
                    BauTextLink("@${user.removePrefix("@")}", onClick = { open(githubUrl(user)) }, modifier = linkModifier.offset(x = (-4).dp))
                }
                e.email?.let { mail ->
                    BauTextLink("Email", onClick = { open("mailto:$mail") }, modifier = linkModifier)
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BauColors.Paper)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(e.roleNote(), fontSize = 14.sp, lineHeight = 19.sp, color = BauColors.TextSecondary)
        }

        sheet.error?.let {
            Text(
                it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 13.sp, lineHeight = 18.sp, color = BauColors.Warning,
            )
        }
    }
}

/** Mobile bio: a modal bottom sheet (swipe down / tap outside / back closes it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeniorEngineerBioSheet(sheet: SeniorEngineerSheet, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BauColors.Surface,
    ) {
        SeniorEngineerBioContent(
            sheet,
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = BauSpacing.screen, end = BauSpacing.screen, bottom = BauSpacing.xxl)
                .windowInsetsPadding(WindowInsets.navigationBars),
        )
    }
}
