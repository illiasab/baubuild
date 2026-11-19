package com.vesper.baubuild.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.vesper.baubuild.core.media.LocalImageStore
import com.vesper.baubuild.core.model.User
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Round profile avatar: the user's photo when we can render it, otherwise their initials on BlueDeep.
 * Decorative (no semantics) — the name is always shown next to it.
 *
 * [image] overrides everything (e.g. a just-picked photo in Edit Profile before it is saved).
 */
@Composable
fun UserAvatar(user: User, size: Dp, modifier: Modifier = Modifier, image: ImageBitmap? = null) {
    val photo = image ?: rememberAvatarBitmap(user.avatarUrl)
    val fontSize = with(LocalDensity.current) { (size * 0.375f).toSp() } // not scaled by font size: fixed-size circle
    Box(
        modifier.size(size).clip(CircleShape).background(BauColors.BlueDeep).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(photo, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Text(user.initials, color = BauColors.Surface, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * Bytes we already hold for [url] (`memory://` avatars, or a photo the user just uploaded), decoded once.
 * TODO(avatar-loading): remote http(s) avatars from other sessions fall back to initials until an image
 *  loader (e.g. Coil 3 multiplatform) is added; plug it in here.
 */
@Composable
private fun rememberAvatarBitmap(url: String?): ImageBitmap? = remember(url) {
    url?.let(LocalImageStore::get)?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
}
