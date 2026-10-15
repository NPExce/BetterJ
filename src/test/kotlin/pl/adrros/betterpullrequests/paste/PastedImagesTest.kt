package pl.adrros.betterpullrequests.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File

class PastedImagesTest {
    @Test
    fun `addresses and names next to an image do not count as text`() {
        assertTrue(PastedImages.isAddressLike(""))
        assertTrue(PastedImages.isAddressLike("https://example.com/a.png"))
        assertTrue(PastedImages.isAddressLike("file:///Users/x/shot.png"))
        assertTrue(PastedImages.isAddressLike("/Users/x/shot.png"))
        assertTrue(PastedImages.isAddressLike("shot.png"))
        assertFalse(PastedImages.isAddressLike("please look at this"))
        assertFalse(PastedImages.isAddressLike("line one\nline two"))
    }

    @Test
    fun `screenshots encode to png`() {
        val image = PastedImages.screenshot({ BufferedImage(3, 2, BufferedImage.TYPE_3BYTE_BGR) })
        val bytes = image.load()
        assertEquals("image/png", image.contentType)
        assertTrue(bytes.size > 8)
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
    }

    @Test
    fun `only image files are taken from a file list`() {
        val png = File.createTempFile("shot", ".PNG").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val txt = File.createTempFile("notes", ".txt").apply { writeText("x"); deleteOnExit() }
        val images = listOf(png, txt).mapNotNull { PastedImages.imageFile(it) }
        assertEquals(1, images.size)
        assertEquals("image/png", images[0].contentType)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(images[0].load()))
    }
}
