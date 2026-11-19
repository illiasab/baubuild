@file:OptIn(ExperimentalForeignApi::class)

package com.vesper.baubuild.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.min

/** iOS: `PHPickerViewController` (no photo-library permission needed), presented from the key window. */
@Composable
actual fun rememberImagePicker(onResult: (PickedImage?) -> Unit): ImagePickerLauncher {
    val currentOnResult by rememberUpdatedState(onResult)
    // The picker holds its delegate weakly; keep it alive for as long as this composable is.
    val delegate = remember { PickerDelegate { currentOnResult(it) } }
    return remember { ImagePickerLauncher { delegate.present() } }
}

private class PickerDelegate(private val onResult: (PickedImage?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    fun present() {
        val config = PHPickerConfiguration().apply {
            setFilter(PHPickerFilter.imagesFilter)
            setSelectionLimit(1)
        }
        val picker = PHPickerViewController(configuration = config)
        picker.delegate = this
        val presenter = topViewController()
        if (presenter == null) {
            onResult(null)
        } else {
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            onResult(null)
            return
        }
        // Called on a background queue; HEIC/PNG/JPEG all decode through UIImage.
        result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val image = data?.let { downscaledJpeg(it) }
            dispatch_async(dispatch_get_main_queue()) { onResult(image) }
        }
    }
}

private fun downscaledJpeg(data: NSData): PickedImage? {
    val source = UIImage.imageWithData(data) ?: return null
    val (w, h) = source.size.useContents { width to height }
    if (w <= 0.0 || h <= 0.0) return null
    val scale = min(1.0, AVATAR_MAX_EDGE_PX / max(w, h))
    val tw = w * scale
    val th = h * scale
    val format = UIGraphicsImageRendererFormat.defaultFormat().apply {
        setScale(1.0)
        setOpaque(true)
    }
    val renderer = UIGraphicsImageRenderer(size = CGSizeMake(tw, th), format = format)
    // drawInRect honours imageOrientation, so camera photos come out upright.
    val output = renderer.imageWithActions { _ -> source.drawInRect(CGRectMake(0.0, 0.0, tw, th)) }
    val jpeg = UIImageJPEGRepresentation(output, AVATAR_JPEG_QUALITY / 100.0) ?: return null
    return PickedImage(jpeg.toByteArray(), "image/jpeg")
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}

private fun topViewController(): UIViewController? {
    val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
    val windows = (scenes.filter { it.activationState == UISceneActivationStateForegroundActive } + scenes)
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
    var top = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
