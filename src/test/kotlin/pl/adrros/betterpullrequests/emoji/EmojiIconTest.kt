package pl.adrros.betterpullrequests.emoji

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class EmojiIconTest {
    @Test
    fun `paints something inside its bounds`() {
        val icon = EmojiIcon("😄", 24)
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        icon.paintIcon(null, g, 0, 0)
        g.dispose()
        val painted = (0 until image.width).sumOf { x -> (0 until image.height).count { y -> image.getRGB(x, y) ushr 24 != 0 } }
        assertTrue("expected visible pixels, got $painted", painted > 0)
    }
}
