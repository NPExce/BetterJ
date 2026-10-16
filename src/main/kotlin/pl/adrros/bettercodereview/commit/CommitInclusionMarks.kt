package pl.adrros.bettercodereview.commit

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.ui.ThreeStateCheckBox
import com.intellij.util.ui.tree.TreeUtil
import pl.adrros.bettercodereview.review.DecorationOwner
import pl.adrros.bettercodereview.review.files.CheckboxClicks
import pl.adrros.bettercodereview.review.files.RowSlots
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * The Commit tool window's checkboxes say which files go into the commit. The plugin adds a third look to them:
 * a dash on a file ticked by hand whose content moved on since (edited by the developer, or written on disk by a
 * tool), and on a ticked file clicked once, which marks it for another look while keeping it in the commit. The
 * next click unticks it as usual, and ticking it again takes the file's current content as the new baseline.
 * Only files ticked by a click get a baseline: files the IDE ticks on its own, or that were ticked before the
 * plugin saw the tree, would otherwise show the dash on every keystroke.
 */
object CommitInclusionMarks {
    const val INSTALLED = "BetterCodeReview.commitMarks"
    private const val SETTING = "BetterCodeReview.commitCheckboxMarks"

    var isEnabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(SETTING, true)
        set(value) = PropertiesComponent.getInstance().setValue(SETTING, value, true)

    private val installed: MutableSet<JTree> = Collections.newSetFromMap(WeakHashMap())

    /** Every local-changes tree is watched; whether it shows checkboxes is checked when painting and clicking, since the Commit panel turns them on later. */
    fun watch(tree: ChangesListView) {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) return }
        install(tree)
    }

    fun install(tree: ChangesTree): InclusionOverlay {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) uninstall(tree) else tree.remove(it) }
        val overlay = InclusionOverlay(tree)
        tree.putClientProperty(INSTALLED, overlay)
        tree.add(overlay)
        tree.addComponentListener(overlay.resizeListener)
        synchronized(installed) { installed.add(tree) }
        CheckboxClicks.register(tree) { point -> overlay.decide(point) }
        tree.repaint()
        return overlay
    }

    fun uninstall(tree: JTree) {
        val overlay = tree.getClientProperty(INSTALLED) as? JComponent
        tree.putClientProperty(INSTALLED, null)
        (tree as? JComponent)?.let(CheckboxClicks::unregister)
        if (overlay != null) {
            (overlay as? InclusionOverlay)?.let {
                tree.removeComponentListener(it.resizeListener)
                it.stopListening()
            }
            tree.remove(overlay)
        }
        synchronized(installed) { installed.remove(tree) }
        tree.repaint()
    }

    fun uninstallAll() {
        synchronized(installed) { installed.toList() }.forEach { tree -> runCatching { uninstall(tree) } }
    }

    /** What identifies a file's content at a moment: the open document's stamp when there is one, and the file's own stamps. */
    data class Stamp(val document: Long, val file: Long, val time: Long) {
        /** A different document stamp counts only when both sides had a document; the file stamps always count. */
        fun changedSince(baseline: Stamp): Boolean =
            (baseline.document >= 0 && document >= 0 && document != baseline.document) || file != baseline.file || time != baseline.time
    }

    /** Replaceable in tests; null when the file is not on disk. */
    var stampOf: (FilePath) -> Stamp? = { path ->
        path.virtualFile?.let { file ->
            val document = if (ApplicationManager.getApplication().isDispatchThread) FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: -1 else -1
            Stamp(document, file.modificationStamp, file.timeStamp)
        }
    }

    /** The file a node stands for: a change by its path after the change (before it, when deleted); ChangesUtil moves between IDE versions, so the revisions are read directly. */
    fun pathOf(userObject: Any?): FilePath? = when (userObject) {
        is Change -> userObject.afterRevision?.file ?: userObject.beforeRevision?.file
        is FilePath -> userObject
        else -> null
    }

    class InclusionOverlay(private val tree: ChangesTree) : JComponent() {
        /** Content baseline of every file ticked by a click, taken right after the tick. */
        private val baselines = HashMap<FilePath, Stamp>()

        /** Files whose tick came from a click on the checkbox; only they get a baseline. */
        private val tickedByClick = HashSet<FilePath>()

        /** Files the developer clicked once more to mark for another look; they stay included. */
        private val flagged = HashSet<FilePath>()

        private var listening: Disposable? = null

        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fitTree()
        }

        init {
            isOpaque = false
            isFocusable = false
            DecorationOwner.mark(this)
            fitTree()
        }

        /** The content listeners live only while the overlay is in a component hierarchy, so a closed project does not keep the tree alive. */
        override fun addNotify() {
            super.addNotify()
            if (listening != null) return
            val disposable = Disposer.newDisposable("BetterCodeReview.commitMarks").also { listening = it }
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (baselines.isEmpty()) return
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (baselines.keys.any { it.path == file.path }) repaintIfShowing()
                }
            }, disposable)
            ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (baselines.isEmpty()) return
                    if (events.any { event -> baselines.keys.any { it.path == event.path } }) repaintIfShowing()
                }
            })
        }

        override fun removeNotify() {
            stopListening()
            super.removeNotify()
        }

        fun stopListening() {
            listening?.let(Disposer::dispose)
            listening = null
        }

        private fun repaintIfShowing() {
            if (tree.isShowing) tree.repaint()
        }

        private fun fitTree() {
            bounds = Rectangle(0, 0, tree.width, tree.height)
        }

        override fun contains(x: Int, y: Int): Boolean = false

        private val active: Boolean get() = CommitInclusionMarks.isEnabled && tree.isShowCheckboxes // qualified: JComponent has its own isEnabled

        /** Fully ticked, not the platform's own "[-]" of a file with only some lines included. */
        private fun isTicked(userObject: Any): Boolean = tree.inclusionModel.getInclusionState(userObject) == ThreeStateCheckBox.State.SELECTED

        /** Brings the baselines in line with what is ticked now: clicked ticks take a baseline, removed ticks drop it and the flag. */
        fun syncInclusion() {
            val included = tree.includedSet.filter { isTicked(it) }.mapNotNull { pathOf(it) }.toSet()
            baselines.keys.retainAll(included)
            flagged.retainAll(included)
            tickedByClick.retainAll(included)
            for (path in tickedByClick) {
                if (path !in baselines) stampOf(path)?.let { baselines[path] = it }
            }
        }

        /** The dash shows on a ticked file that is flagged or whose content moved away from its baseline (a deletion counts). */
        fun isMarked(path: FilePath): Boolean {
            if (path in flagged) return true
            val baseline = baselines[path] ?: return false
            val now = stampOf(path) ?: return true
            return now.changedSince(baseline)
        }

        fun isFlagged(path: FilePath): Boolean = path in flagged

        override fun paintComponent(g: Graphics) {
            if (!active) return
            syncInclusion()
            val visible = tree.visibleRect
            val clip = g.clipBounds
            val first = tree.getClosestRowForLocation(visible.x, visible.y).coerceAtLeast(0)
            val last = tree.getClosestRowForLocation(visible.x, visible.y + visible.height - 1)
            for (row in first..last) {
                val path = tree.getPathForRow(row) ?: continue
                val bounds = tree.getRowBounds(row) ?: continue
                if (clip != null && !clip.intersects(Rectangle(visible.x, bounds.y, visible.width, bounds.height))) continue
                if (!tree.model.isLeaf(path.lastPathComponent)) continue
                val userObject = TreeUtil.getLastUserObject(path) ?: continue
                val filePath = pathOf(userObject) ?: continue
                if (!isTicked(userObject) || !isMarked(filePath)) continue
                val slot = RowSlots.stateSlot(tree, path, row) ?: continue
                RowSlots.paintDash(g, tree, row, slot, isCurrent = false)
            }
        }

        /**
         * A press on the checkbox of a ticked, unmarked file flags it and keeps the platform from unticking it. A press
         * on an unticked file is the platform's, but remembered as a tick by hand. Everything else is the platform's.
         */
        fun decide(point: Point): CheckboxClicks.Decision? {
            if (!active) return null
            val row = tree.getClosestRowForLocation(point.x, point.y).takeIf { it >= 0 } ?: return null
            val path = tree.getPathForRow(row) ?: return null
            if (!tree.model.isLeaf(path.lastPathComponent)) return null
            val userObject = TreeUtil.getLastUserObject(path) ?: return null
            val filePath = pathOf(userObject) ?: return null
            val slot = RowSlots.stateSlot(tree, path, row) ?: return null
            if (!slot.bounds.contains(point)) return null
            syncInclusion()
            val state = tree.inclusionModel.getInclusionState(userObject)
            return when {
                state == ThreeStateCheckBox.State.NOT_SELECTED -> CheckboxClicks.Decision(consume = false) {
                    tickedByClick.add(filePath)
                    tree.repaint()
                }
                state == ThreeStateCheckBox.State.SELECTED && !isMarked(filePath) -> CheckboxClicks.Decision(consume = true) {
                    tree.selectionPath = path // the consumed release skips the tree's own selection handling
                    flagged.add(filePath)
                    tree.repaint()
                }
                else -> null // the platform unticks a marked file; a partially included file is the platform's alone
            }
        }
    }

    class ToggleMarksAction : ToggleAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = isEnabled
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            isEnabled = state
        }
    }
}
