package pl.adrros.bettercodereview.emoji

import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Icon

/**
 * Draws an emoji glyph straight through the graphics pipeline, which resolves the colour emoji font natively.
 * Putting the glyph into a lookup's text instead makes the platform probe every installed font for it, and
 * with a few hundred items that froze the UI for a good 20 seconds.
 */
class EmojiIcon(private val glyph: String, private val size: Int) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(size)

    override fun getIconHeight(): Int = JBUI.scale(size)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAntialiasing(g2)
            g2.font = fontFor(c)
            val metrics = g2.fontMetrics
            val textX = x + (iconWidth - metrics.stringWidth(glyph)) / 2
            val textY = y + (iconHeight - metrics.height) / 2 + metrics.ascent
            g2.drawString(glyph, textX, textY)
        } finally {
            g2.dispose()
        }
    }

    private fun fontFor(c: Component?): Font = (c?.font ?: JBFont.label()).deriveFont(Font.PLAIN, iconHeight * 0.72f)
}
