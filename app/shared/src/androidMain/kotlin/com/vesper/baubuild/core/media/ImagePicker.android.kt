package com.vesper.baubuild.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Android Photo Picker (`PickVisualMedia`, images only). Registered through the Compose activity-result
 * registry of the hosting `ComponentActivity`, so MainActivity needs no extra setup and no storage permission.
 */
@Composable
actual fun rememberImagePicker(onResult: (PickedImage?) -> Unit): ImagePickerLauncher {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            currentOnResult(null)
        } else {
            scope.launch {
                val image = withContext(Dispatchers.IO) { readDownscaled(context, uri) }
                currentOnResult(image)
            }
        }
    }
    return remember(launcher) {
        ImagePickerLauncher { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
}

/** Decodes with subsampling, applies EXIF rotation, scales to [AVATAR_MAX_EDGE_PX] and re-encodes as JPEG. */
private fun readDownscaled(context: Context, uri: Uri): PickedImage? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_MAX_EDGE_PX) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (decoded == null) {
            null
        } else {
            val rotation = resolver.openInputStream(uri)?.use {
                runCatching { ExifInterface(it).rotationDegrees() }.getOrDefault(0)
            } ?: 0
            val longest = max(decoded.width, decoded.height)
            val scale = if (longest > AVATAR_MAX_EDGE_PX) AVATAR_MAX_EDGE_PX.toFloat() / longest else 1f
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0) postRotate(rotation.toFloat())
            }
            val output = if (matrix.isIdentity) decoded
            else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val bytes = ByteArrayOutputStream().use { out ->
                output.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
                out.toByteArray()
            }
            PickedImage(bytes, "image/jpeg")
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}

private fun ExifInterface.rotationDegrees(): Int =
    when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }
