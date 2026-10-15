package pl.adrros.betterpullrequests.paste

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.util.ui.ImageUtil
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

class PastedImage(val fileName: String, val contentType: String, val bytes: ByteArray)

/** Reads images off the clipboard: a screenshot (raw image data) or image files copied from a file manager. */
object PastedImages {
    private val IMAGE_CONTENT_TYPES = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
    )
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** True when the clipboard holds an image or files and no text, so a paste cannot mean "paste text". */
    fun clipboardHasImagesOnly(): Boolean {
        val clipboard = CopyPasteManager.getInstance()
        if (clipboard.areDataFlavorsAvailable(DataFlavor.stringFlavor)) return false
        return clipboard.areDataFlavorsAvailable(DataFlavor.imageFlavor) || clipboard.areDataFlavorsAvailable(DataFlavor.javaFileListFlavor)
    }

    fun fromClipboard(): List<PastedImage> {
        val clipboard = CopyPasteManager.getInstance()
        if (!clipboardHasImagesOnly()) return emptyList()
        if (clipboard.areDataFlavorsAvailable(DataFlavor.imageFlavor)) {
            val image = clipboard.getContents<Image>(DataFlavor.imageFlavor) ?: return emptyList()
            return listOf(screenshot(image))
        }
        if (clipboard.areDataFlavorsAvailable(DataFlavor.javaFileListFlavor)) {
            val files = clipboard.getContents<List<File>>(DataFlavor.javaFileListFlavor) ?: return emptyList()
            return files.mapNotNull { imageFile(it) }
        }
        return emptyList()
    }

    fun screenshot(image: Image, now: LocalDateTime = LocalDateTime.now()): PastedImage {
        val buffered = ImageUtil.toBufferedImage(image)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
        return PastedImage("screenshot-${TIMESTAMP.format(now)}.png", "image/png", bytes)
    }

    fun imageFile(file: File): PastedImage? {
        val contentType = IMAGE_CONTENT_TYPES[file.extension.lowercase()] ?: return null
        if (!file.isFile) return null
        return PastedImage(file.name, contentType, file.readBytes())
    }
}
