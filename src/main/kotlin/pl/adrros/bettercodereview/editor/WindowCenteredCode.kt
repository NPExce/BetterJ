package pl.adrros.bettercodereview.editor

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.TextAnnotationGutterProvider
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import pl.adrros.bettercodereview.settings.Feature
import pl.adrros.bettercodereview.settings.Features
import java.awt.Color
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

private val LOG = com.intellij.openapi.diagnostic.logger<WindowCenteredCode>()

/**
 * Centres the code on the IDE window instead of on the editor: an empty annotation left of the line numbers pushes
 * the text so its right-margin column sits in the middle of the window. The strip's width is worked out while the
 * editor lays itself out, in the same pass that moves it, so a tool window resized on either side leaves the code
 * where it was. That pass runs in the IDE's Center View mode, which the feature switches on with its own margin at zero.
 */
object WindowCenteredCode {
    private const val IDE_CENTER_VIEW = "editor.distraction.free.mode"
    private const val IDE_CENTER_MARGIN = "editor.distraction.free.margin"
    private const val SAVED_CENTER_VIEW = "BetterCodeReview.windowCenteredCode.savedCenterView"
    private const val SAVED_CENTER_MARGIN = "BetterCodeReview.windowCenteredCode.savedCenterMargin"
    private val PAD = Key.create<Pad>("BetterCodeReview.windowCenteredPad")

    val isEnabled: Boolean get() = Feature.CENTER_ON_WINDOW.isOn

    fun refreshAll() {
        EditorFactory.getInstance().allEditors.forEach(::attach)
    }

    /** On plugin unload, so a newer version starts from editors without the pad. */
    fun removeAll() {
        for (editor in EditorFactory.getInstance().allEditors) {
            val pad = editor.getUserData(PAD) ?: continue
            pad.detach()
            editor.putUserData(PAD, null)
        }
    }

    private fun attach(editor: Editor) {
        if (editor.editorKind != EditorKind.MAIN_EDITOR || editor !is EditorEx || editor.isDisposed) return
        val pad = editor.getUserData(PAD) ?: Pad(editor).also { editor.putUserData(PAD, it) }
        pad.apply()
    }

    /**
     * Where the text should start on the window: in its middle for a [column] pixels wide text, as far right as the
     * editor still shows the whole column ([textLeft] and [visibleWidth] are the text area now, behind [currentPad]
     * pixels of pad), and never left of where it would start without the pad.
     */
    fun targetTextLeft(windowWidth: Int, textLeft: Int, visibleWidth: Int, column: Int, currentPad: Int): Int {
        val middle = (windowWidth - column) / 2
        val fitting = textLeft + visibleWidth - column
        return middle.coerceAtMost(fitting).coerceAtLeast(textLeft - currentPad)
    }

    private const val TOLERANCE_PX = 2

    /**
     * The text's place on the window, moved to a new target over [GLIDE_MS] with an ease-out instead of at once.
     * Differences of [TOLERANCE_PX] or less are not animated.
     */
    class Glide(private val timer: (() -> Unit) -> javax.swing.Timer = { tick -> javax.swing.Timer(FRAME_MS) { tick() } }) {
        private var shown: Int? = null
        private var from = 0
        private var to = 0
        private var started = 0L
        private var ticking: javax.swing.Timer? = null

        /** The place to show the text at [now]; [tick] is called every frame while a glide runs. */
        fun positionAt(target: Int, now: Long, tick: () -> Unit): Int {
            val current = shown ?: target.also { shown = it }
            if (kotlin.math.abs(target - to) > TOLERANCE_PX || ticking == null && kotlin.math.abs(target - current) > TOLERANCE_PX) {
                if (kotlin.math.abs(target - current) <= TOLERANCE_PX) {
                    to = target
                } else {
                    from = current
                    to = target
                    started = now
                    if (ticking == null) ticking = timer(tick).also { it.start() }
                }
            }
            val running = ticking ?: return current
            val progress = ((now - started).toDouble() / GLIDE_MS).coerceIn(0.0, 1.0)
            val eased = 1 - (1 - progress) * (1 - progress) * (1 - progress)
            val position = (from + (to - from) * eased).roundToInt()
            shown = position
            if (progress >= 1.0) {
                running.stop()
                ticking = null
            }
            return position
        }

        val isRunning: Boolean get() = ticking != null

        fun stop() {
            ticking?.stop()
            ticking = null
        }
    }

    private const val GLIDE_MS = 180L
    private const val FRAME_MS = 15

    /**
     * An empty annotation whose width is asked for while the editor lays itself out. It keeps the parts of the
     * editor that do not depend on it (the gutter without the strip, the scroll bar) from the last layout, and
     * works out the strip from where the editor stands now.
     */
    private class Pad(private val editor: EditorEx) : TextAnnotationGutterProvider.Filler {
        private val glide = Glide()
        private var shown = false
        private var px = 0

        /** The text's offset from the scroll pane's left edge without the strip, and the scroll pane's part right of the text. */
        private var gutterWithoutPad = -1
        private var rightOfText = 0
        private var lastKey: List<Int>? = null

        private val laidOut = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = remember()
            override fun componentMoved(e: ComponentEvent) = remember()
        }
        private val moved = object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent) = relayout()
            override fun ancestorResized(e: HierarchyEvent) = relayout()
        }

        init {
            editor.scrollPane.viewport.addComponentListener(laidOut)
            editor.component.addHierarchyBoundsListener(moved)
        }

        fun apply() {
            if (editor.scrollPane.viewport.width > 0) remember()
            if (isEnabled && !shown) {
                editor.gutter.registerTextAnnotation(this)
                shown = true
            } else if (!isEnabled && shown) {
                editor.gutter.closeTextAnnotations(listOf(this))
                shown = false
            }
            relayout()
        }

        fun detach() {
            glide.stop()
            editor.scrollPane.viewport.removeComponentListener(laidOut)
            editor.component.removeHierarchyBoundsListener(moved)
            if (shown) editor.gutter.closeTextAnnotations(listOf(this))
            shown = false
        }

        /** Asks the gutter for its size again; in the IDE's Center View mode the editor's own layout does this too. */
        private fun relayout() {
            if (editor.isDisposed) return
            lastKey = null
            editor.gutterComponentEx.revalidateMarkup()
        }

        /** After a layout: what the text's place is made of, apart from the strip. */
        private fun remember() {
            val viewport = editor.scrollPane.viewport
            val measured = viewport.x - px
            val right = editor.scrollPane.width - viewport.x - viewport.width
            if (measured != gutterWithoutPad || right != rightOfText) {
                val first = gutterWithoutPad < 0
                gutterWithoutPad = measured
                rightOfText = right
                if (first) relayout()
            }
        }

        override fun getWidth(): Int {
            if (!isEnabled || editor.isDisposed || gutterWithoutPad < 0) return 0
            val root = SwingUtilities.getRootPane(editor.component) ?: return px
            val scrollPane = editor.scrollPane
            if (root.width <= 0 || scrollPane.width <= 0) return px
            val column = editor.settings.getRightMargin(editor.project) * EditorUtil.getSpaceWidth(Font.PLAIN, editor)
            if (column <= 0) return 0
            val paneLeft = SwingUtilities.convertPoint(scrollPane, 0, 0, root).x
            // Asked once per line of the file: one answer per layout, or per frame while gliding.
            val frame = if (glide.isRunning) (System.currentTimeMillis() / FRAME_MS).toInt() else 0
            val key = listOf(paneLeft, scrollPane.width, root.width, column, gutterWithoutPad, rightOfText, frame)
            if (key == lastKey) return px
            lastKey = key
            val textLeft = paneLeft + gutterWithoutPad + px
            val visible = scrollPane.width - gutterWithoutPad - px - rightOfText
            val target = targetTextLeft(root.width, textLeft, visible, column, px)
            val place = glide.positionAt(target, System.currentTimeMillis()) { relayout() }
            val width = (place - paneLeft - gutterWithoutPad).coerceIn(0, root.width / 2)
            if (kotlin.math.abs(width - px) > TOLERANCE_PX * 10) {
                LOG.info("Center on window: window ${root.width}, editor from $paneLeft, column $column, pad $px -> $width px")
            }
            px = width
            return width
        }

        override fun useMargin(): Boolean = false
        override fun getLineText(line: Int, editor: Editor): String = ""
        override fun getToolTip(line: Int, editor: Editor): String? = null
        override fun getStyle(line: Int, editor: Editor): EditorFontType = EditorFontType.PLAIN
        override fun getColor(line: Int, editor: Editor): ColorKey? = null
        override fun getBgColor(line: Int, editor: Editor): Color? = null
        override fun getPopupActions(line: Int, editor: Editor): List<AnAction> = emptyList()

        /** Closed from the gutter's "Close Annotations": registered again at the next switch or editor. */
        override fun gutterClosed() {
            shown = false
        }
    }

    class EditorListener : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
            if (isEnabled) attach(event.editor)
        }
    }

    class ToggleCenterAction : com.intellij.openapi.actionSystem.ToggleAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = isEnabled
        override fun setSelected(e: AnActionEvent, state: Boolean) = Features.set(Feature.CENTER_ON_WINDOW, state)
    }

    /**
     * Switching on turns the IDE's Center View mode on with its margin at zero, so the editor asks for the strip on
     * every layout and adds nothing of its own; switching off puts both back as they were.
     */
    fun switched(on: Boolean) {
        val properties = PropertiesComponent.getInstance()
        if (on) {
            if (properties.getValue(SAVED_CENTER_VIEW) == null) {
                properties.setValue(SAVED_CENTER_VIEW, Registry.`is`(IDE_CENTER_VIEW, false).toString())
                properties.setValue(SAVED_CENTER_MARGIN, AdvancedSettings.getInt(IDE_CENTER_MARGIN).toString())
            }
            ensureIdeMode()
        } else {
            properties.getValue(SAVED_CENTER_VIEW)?.let { Registry.get(IDE_CENTER_VIEW).setValue(it.toBoolean()) }
            properties.getValue(SAVED_CENTER_MARGIN)?.toIntOrNull()?.let { AdvancedSettings.setInt(IDE_CENTER_MARGIN, it) }
            properties.unsetValue(SAVED_CENTER_VIEW)
            properties.unsetValue(SAVED_CENTER_MARGIN)
            reinitEditors()
        }
        refreshAll()
    }

    /** At startup and on switching on: the mode the strip relies on, in case it was changed meanwhile. */
    fun ensureIdeMode() {
        if (!isEnabled) return
        var changed = false
        if (!Registry.`is`(IDE_CENTER_VIEW, false)) {
            Registry.get(IDE_CENTER_VIEW).setValue(true)
            changed = true
        }
        if (AdvancedSettings.getInt(IDE_CENTER_MARGIN) != 0) {
            AdvancedSettings.setInt(IDE_CENTER_MARGIN, 0)
            changed = true
        }
        if (changed) reinitEditors()
    }

    private fun reinitEditors() {
        EditorFactory.getInstance().allEditors.filterIsInstance<EditorEx>().forEach { it.reinitSettings() }
    }
}
