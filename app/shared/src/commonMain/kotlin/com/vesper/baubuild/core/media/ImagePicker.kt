package com.vesper.baubuild.core.media

import androidx.compose.runtime.Composable

/** Encoded image bytes picked by the user. Platforms deliver a downscaled JPEG (see [AVATAR_MAX_EDGE_PX]). */
class PickedImage(val bytes: ByteArray, val mimeType: String)

fun interface ImagePickerLauncher {
    fun launch()
}

/**
 * System photo picker: Android Photo Picker, iOS `PHPickerViewController`, desktop `FileDialog` (JPEG/PNG).
 * [onResult] runs on the main thread with the picked image, or null when the user cancelled or the file
 * couldn't be read. No storage permission is needed on any platform.
 */
@Composable
expect fun rememberImagePicker(onResult: (PickedImage?) -> Unit): ImagePickerLauncher

/** Longest edge of an uploaded profile photo. Bigger photos are scaled down before upload. */
const val AVATAR_MAX_EDGE_PX = 1024

/** JPEG quality (0..100) used when re-encoding a picked photo. */
const val AVATAR_JPEG_QUALITY = 85
