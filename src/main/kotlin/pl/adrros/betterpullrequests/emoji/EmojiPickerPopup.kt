package pl.adrros.betterpullrequests.emoji

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/**
 * A grid of every GitHub emoji with a search box on top and the recently used ones first.
 * Typing filters, arrows move around the grid, Enter or a click inserts.
 */
class EmojiPickerPopup(
    private val catalog: EmojiCatalog,
    private val recent: RecentEmoji,
    private val onChosen: (Emoji) -> Unit,
) {
    private val searchField = JBTextField().apply {
        emptyText.text = BetterPullRequestsBundle.message("emoji.picker.search")
    }
    private val recentLabel = sectionLabel(BetterPullRequestsBundle.message("emoji.picker.recent"))
    private val recentGrid = EmojiGrid()
    private val allLabel = sectionLabel(BetterPullRequestsBundle.message("emoji.picker.all"))
    private val allGrid = EmojiGrid()
    private val nameLabel = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 6) }
    private var popup: JBPopup? = null

    fun show(editor: Editor) {
        val content = buildContent()
        applyFilter("")
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, searchField)
            .setTitle(BetterPullRequestsBundle.message("emoji.picker.title"))
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setMinSize(JBUI.size(MIN_WIDTH, MIN_HEIGHT))
            .createPopup()
            .also { it.showInBestPositionFor(editor) }
    }

    private fun buildContent(): JComponent {
        val sections = ScrollableColumn().apply {
            add(recentLabel)
            add(recentGrid)
            add(allLabel)
            add(allGrid)
        }
        val scroll = JBScrollPane(sections).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
        }
        listOf(recentGrid, allGrid).forEach(::wireGrid)
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter(searchField.text)
        })
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        e.consume()
                        chooseFirstVisible()
                    }
                    KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT -> {
                        e.consume()
                        focusGrid(if (recentGrid.isVisible) recentGrid else allGrid)
                    }
                }
            }
        })
        return JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(MIN_WIDTH, PREFERRED_HEIGHT)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6)
                add(searchField, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(nameLabel, BorderLayout.SOUTH)
        }
    }

    private fun wireGrid(grid: EmojiGrid) {
        grid.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                grid.emojiAt(e)?.let(::choose)
            }
        })
        grid.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                showName(grid.emojiAt(e))
            }
        })
        grid.addListSelectionListener { showName(grid.selectedValue) }
        grid.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER -> {
                        e.consume()
                        grid.selectedValue?.let(::choose)
                    }
                    e.keyCode == KeyEvent.VK_UP && grid.selectedIndex < grid.columns() && grid === allGrid && recentGrid.isVisible -> {
                        e.consume()
                        focusGrid(recentGrid)
                    }
                    e.keyCode == KeyEvent.VK_DOWN && grid === recentGrid -> {
                        e.consume()
                        focusGrid(allGrid)
                    }
                    e.keyChar.isLetterOrDigit() || e.keyCode == KeyEvent.VK_BACK_SPACE -> {
                        searchField.requestFocusInWindow()
                        searchField.dispatchEvent(e)
                    }
                }
            }
        })
    }

    private fun applyFilter(query: String) {
        val recents = if (query.isBlank()) recent.emoji(catalog) else emptyList()
        recentLabel.isVisible = recents.isNotEmpty()
        recentGrid.isVisible = recents.isNotEmpty()
        recentGrid.setListData(recents.toTypedArray())
        allGrid.setListData(catalog.search(query.trim()).toTypedArray())
        allGrid.selectedIndex = if (allGrid.model.size > 0) 0 else -1
        allGrid.ensureIndexIsVisible(0)
        showName(allGrid.selectedValue)
    }

    private fun focusGrid(grid: EmojiGrid) {
        if (grid.model.size == 0) return
        if (grid.selectedIndex < 0) grid.selectedIndex = 0
        grid.requestFocusInWindow()
    }

    private fun chooseFirstVisible() {
        val first = allGrid.selectedValue ?: allGrid.model.takeIf { it.size > 0 }?.getElementAt(0) ?: return
        choose(first)
    }

    private fun choose(emoji: Emoji) {
        popup?.closeOk(null)
        recent.remember(emoji)
        onChosen(emoji)
    }

    private fun showName(emoji: Emoji?) {
        nameLabel.text = emoji?.shortcode ?: " "
    }

    private fun sectionLabel(text: String): JBLabel = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(6, 8, 2, 8)
    }

    private class EmojiGrid : JBList<Emoji>() {
        init {
            layoutOrientation = JList.HORIZONTAL_WRAP
            visibleRowCount = -1
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellWidth = JBUI.scale(CELL_SIZE)
            fixedCellHeight = JBUI.scale(CELL_SIZE)
            border = JBUI.Borders.empty(0, 4)
            cellRenderer = EmojiCellRenderer()
        }

        fun emojiAt(e: MouseEvent): Emoji? {
            val index = locationToIndex(e.point)
            if (index < 0 || !getCellBounds(index, index).contains(e.point)) return null
            return model.getElementAt(index)
        }

        fun columns(): Int = maxOf(1, width / maxOf(1, fixedCellWidth))
    }

    private class EmojiCellRenderer : JBLabel(), javax.swing.ListCellRenderer<Emoji> {
        init {
            horizontalAlignment = SwingConstants.CENTER
            isOpaque = true
        }

        override fun getListCellRendererComponent(
            list: JList<out Emoji>, value: Emoji?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            icon = value?.let { EmojiIcon(it.glyph, ICON_SIZE) }
            toolTipText = value?.shortcode
            background = if (isSelected) UIUtil.getListSelectionBackground(true) else list.background
            return this
        }
    }

    /** Lays the grids out top to bottom and lets them wrap to the viewport width. */
    private class ScrollableColumn : JPanel(VerticalLayout(0)), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(CELL_SIZE)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    companion object {
        private const val CELL_SIZE = 34
        private const val ICON_SIZE = 24
        private const val MIN_WIDTH = 360
        private const val MIN_HEIGHT = 300
        private const val PREFERRED_HEIGHT = 420
    }
}
