package pl.adrros.bettercodereview.paste

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.util.ui.ImageUtil
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/** An image waiting to be uploaded. Reading and encoding happens in [load], off the UI thread. */
class PastedImage(val fileName: String, val contentType: String, val load: () -> ByteArray)

/**
 * Reads images off the clipboard: a screenshot (raw image data) or image files copied from a file manager.
 * Text next to an image is fine when it is only the image's address or name (browsers and file managers add that);
 * real text wins over an image, because then the user most likely meant to paste the text.
 */
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

    /** @param respectText false when the user asked for the image explicitly (button, menu), true for a plain paste. */
    fun fromClipboard(respectText: Boolean = true): List<PastedImage> {
        val contents = CopyPasteManager.getInstance().contents ?: return emptyList()
        return fromTransferable(contents, respectText)
    }

    fun fromTransferable(contents: Transferable, respectText: Boolean = true): List<PastedImage> {
        if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            // File managers add the file names as text and sometimes the icon as an image; the files are what counts.
            @Suppress("UNCHECKED_CAST")
            val files = runCatching { contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<File> }.getOrNull() ?: return emptyList()
            return files.mapNotNull { imageFile(it) }
        }
        if (respectText && !textAllowsImage(contents)) return emptyList()
        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            return listOf(screenshot(image = { contents.getTransferData(DataFlavor.imageFlavor) as Image }))
        }
        return emptyList()
    }

    fun textAllowsImage(contents: Transferable): Boolean {
        if (!contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return true
        val text = runCatching { contents.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull() ?: return true
        return isAddressLike(text)
    }

    /** A URL, a file path or a bare file name: what clipboards put next to an image, never prose. */
    fun isAddressLike(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.any { it == '\n' || it == '\r' }) return false
        return trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file:") ||
            trimmed.startsWith("/") || !trimmed.contains(' ')
    }

    fun screenshot(image: () -> Image, now: LocalDateTime = LocalDateTime.now()): PastedImage =
        PastedImage("screenshot-${TIMESTAMP.format(now)}.png", "image/png") { encodePng(image()) }

    fun encodePng(image: Image): ByteArray {
        val source = ImageUtil.toBufferedImage(image)
        val argb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB).also { target ->
            val g = target.createGraphics()
            try {
                g.drawImage(source, 0, 0, null)
            } finally {
                g.dispose()
            }
        }
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(argb, "png", out)) throw IOException("No PNG encoder for the clipboard image")
        return out.toByteArray()
    }

    fun imageFile(file: File): PastedImage? {
        val contentType = IMAGE_CONTENT_TYPES[file.extension.lowercase()] ?: return null
        if (!file.isFile) return null
        return PastedImage(file.name, contentType) {
            if (file.length() > MAX_BYTES) throw IOException("${file.name} is ${file.length() / 1_000_000} MB; GitHub accepts attachments up to 10 MB")
            file.readBytes()
        }
    }

    /** GitHub rejects larger image attachments. */
    const val MAX_BYTES: Long = 10L * 1024 * 1024
}
