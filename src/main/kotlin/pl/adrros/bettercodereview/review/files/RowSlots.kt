package pl.adrros.bettercodereview.review.files

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.JBColor
import com.intellij.ui.hover.TreeHoverListener
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath

private val LOG = logger<RowSlots>()

/**
 * The checkbox-like slot of a tree row and the dash painted into it. Two trees share this: the pull request's
 * change tree, whose slot is the "viewed" checkbox or, when the file is not viewed and not hovered, the unread
 * dot; and the Commit tool window's tree, whose slot is the inclusion checkbox. The slot is found by laying the
 * row's renderer out at the row's painted size, the same way the platform hit-tests its checkboxes.
 */
object RowSlots {
    /** The dash: the theme's selected-checkbox colour, so it reads as part of the checkbox. */
    val DASH: Color = JBColor.namedColor("BetterCodeReview.ViewedDismissed.dash", JBColor.namedColor("Checkbox.Background.Selected", JBColor(Color(0x3D, 0x74, 0xF0), Color(0x5B, 0x8C, 0xFF))))

    /** On the purple current-file tint the blue dash would fade to about 1.5:1; near-black (light) and white (dark) read there. */
    val DASH_ON_CURRENT: Color = JBColor.namedColor("BetterCodeReview.ViewedDismissed.dashOnCurrent", JBColor(Color(0x1F, 0x1F, 0x1F), Color.WHITE))

    /** The dash drawn inside a filled checkbox, like the platform's indeterminate state. */
    val DASH_IN_BOX: Color = JBColor.namedColor("BetterCodeReview.ViewedDismissed.dashInBox", JBColor.namedColor("Checkbox.Foreground.Selected", JBColor(Color.WHITE, Color.WHITE)))

    class Slot(val bounds: Rectangle, val isCheckbox: Boolean)

    // The unread-dot icon lives in experimental API; a build that renames it must not fail every paint.
    private var unreadIconAvailable = true
    private var missingLogged = false

    /** The viewed or inclusion checkbox, or the unread dot the collaboration tools show for a file without a tick. */
    fun isStateSlot(component: Component): Boolean {
        if (component is JCheckBox) return true
        if (component !is JLabel || component.icon == null || !unreadIconAvailable) return false
        return try {
            component.icon === icons.CollaborationToolsIcons.Review.FileUnread
        } catch (e: LinkageError) {
            unreadIconAvailable = false
            false
        }
    }

    /** Where a row's renderer is painted: the platform UI stretches it to the visible width, which puts a right-hand slot at the edge. */
    fun paintedRowBounds(tree: JTree, path: TreePath): Rectangle? = actualBounds(tree, path) ?: tree.getPathBounds(path)

    // CustomBoundsTreeUI is experimental API; a build without it must not fail every paint.
    private var customBoundsAvailable = true

    private fun actualBounds(tree: JTree, path: TreePath): Rectangle? {
        if (!customBoundsAvailable) return null
        return try {
            (tree.ui as? com.intellij.ui.tree.ui.CustomBoundsTreeUI)?.getActualPathBounds(tree, path)
        } catch (e: LinkageError) {
            customBoundsAvailable = false
            null
        }
    }

    /** The row's slot in tree coordinates, or null when the renderer shows none (a folder, a loading state). */
    fun stateSlot(tree: JTree, path: TreePath, row: Int): Slot? {
        val bounds = paintedRowBounds(tree, path) ?: return null
        val node = path.lastPathComponent
        val leaf = tree.model.isLeaf(node)
        val renderer = tree.cellRenderer.getTreeCellRendererComponent(tree, node, tree.isRowSelected(row), tree.isExpanded(row), leaf, row, false) as? JComponent ?: return null
        renderer.setBounds(0, 0, bounds.width, bounds.height)
        UIUtil.layoutRecursively(renderer)
        val slot = UIUtil.uiTraverser(renderer).filter(Component::class.java).firstOrNull { isStateSlot(it) && it.isVisible }
        if (slot == null || slot.width == 0) {
            if (!missingLogged) {
                missingLogged = true
                LOG.info("Row slot not found: the renderer has no checkbox or unread dot (renderer ${renderer.javaClass.name})")
            }
            return null
        }
        val inRenderer = SwingUtilities.convertRectangle(slot.parent, slot.bounds, renderer)
        inRenderer.translate(bounds.x, bounds.y)
        return Slot(inRenderer, slot is JCheckBox)
    }

    /**
     * Paints the dash. In a checkbox it fills the box the way the platform draws an indeterminate checkbox, so a
     * ticked box and an empty one both turn into "[-]". Over the unread dot it draws the dash with a halo in the
     * row's background, except on the current-file row, where the halo would punch a hole into the tint.
     */
    fun paintDash(g: Graphics, tree: JTree, row: Int, slot: Slot, isCurrent: Boolean) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val box = slot.bounds
            val side = minOf(box.width, box.height)
            if (slot.isCheckbox) {
                val inset = JBUI.scale(2)
                val size = side - 2 * inset
                val x = box.x + (box.width - size) / 2
                val y = box.y + (box.height - size) / 2
                val arc = JBUI.scale(4)
                g2.color = DASH
                g2.fillRoundRect(x, y, size, size, arc, arc)
                val dashWidth = (size * 0.55).toInt().coerceAtLeast(JBUI.scale(6))
                val dashHeight = JBUI.scale(2)
                g2.color = DASH_IN_BOX
                g2.fillRect(x + (size - dashWidth) / 2, y + (size - dashHeight) / 2, dashWidth, dashHeight)
            } else {
                val dashWidth = (side * 0.6).toInt().coerceAtLeast(JBUI.scale(8))
                val dashHeight = JBUI.scale(2)
                val x = box.x + (box.width - dashWidth) / 2
                val y = box.y + (box.height - dashHeight) / 2
                if (isCurrent) {
                    g2.color = DASH_ON_CURRENT
                } else {
                    val hovered = TreeHoverListener.getHoveredRow(tree) == row
                    if (!hovered) {
                        g2.color = if (tree.isRowSelected(row)) RenderingUtil.getSelectionBackground(tree) else RenderingUtil.getBackground(tree)
                        g2.fillRect(x - JBUI.scale(1), y - JBUI.scale(2), dashWidth + 2 * JBUI.scale(1), dashHeight + 2 * JBUI.scale(2))
                    }
                    g2.color = DASH
                }
                g2.fillRect(x, y, dashWidth, dashHeight)
            }
        } finally {
            g2.dispose()
        }
    }
}
