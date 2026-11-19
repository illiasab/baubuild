package com.vesper.baubuild.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

private val imageExtensions = setOf("jpg", "jpeg", "png")

/** Desktop: native file dialog filtered to JPEG/PNG. The dialog is modal; reading/scaling runs off the UI thread. */
@Composable
actual fun rememberImagePicker(onResult: (PickedImage?) -> Unit): ImagePickerLauncher {
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    return remember {
        ImagePickerLauncher {
            val dialog = FileDialog(null as Frame?, "Choose a profile photo", FileDialog.LOAD).apply {
                isMultipleMode = false
                setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in imageExtensions }
                file = "*.jpg;*.jpeg;*.png" // Windows ignores FilenameFilter; this pattern filters there.
                isVisible = true // blocks until closed
            }
            val file = dialog.files.firstOrNull()
            if (file == null) {
                currentOnResult(null)
            } else {
                scope.launch {
                    val image = withContext(Dispatchers.IO) { readDownscaled(file) }
                    currentOnResult(image)
                }
            }
        }
    }
}

private fun readDownscaled(file: File): PickedImage? = try {
    val source = ImageIO.read(file)
    if (source == null) {
        null
    } else {
        val longest = max(source.width, source.height)
        val scale = if (longest > AVATAR_MAX_EDGE_PX) AVATAR_MAX_EDGE_PX.toDouble() / longest else 1.0
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        // JPEG has no alpha: paint onto white so transparent PNGs don't turn black.
        val target = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        target.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            color = Color.WHITE
            fillRect(0, 0, w, h)
            drawImage(source, 0, 0, w, h, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream().use { out ->
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = AVATAR_JPEG_QUALITY / 100f
                }
                writer.write(null, IIOImage(target, null, null), params)
                writer.dispose()
            }
            out.toByteArray()
        }
        PickedImage(bytes, "image/jpeg")
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}
