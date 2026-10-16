package pl.adrros.bettercodereview.commit

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.ui.tree.TreeUtil
import pl.adrros.bettercodereview.review.DecorationOwner
import pl.adrros.bettercodereview.review.files.CheckboxClicks
import pl.adrros.bettercodereview.review.files.RowSlots
import java.awt.Component
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
 * a dash on a ticked file that changed since it was ticked (by the developer or by a tool editing on disk), and
 * on a ticked file clicked once, which marks it for another look while keeping it in the commit. The next click
 * unticks it as usual, and ticking it again takes the file's current content as the new baseline.
 */
object CommitInclusionMarks {
    const val INSTALLED = "BetterCodeReview.commitMarks"

    private val installed: MutableSet<JTree> = Collections.newSetFromMap(WeakHashMap())

    /** The local changes tree of the Commit tool window; other change lists with checkboxes (shelf, commit dialog) qualify too. */
    fun isCommitTree(component: Component): Boolean = component is ChangesListView && component.isShowCheckboxes

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
        overlay.syncInclusion() // files already ticked take their current content as the baseline
        tree.repaint()
        return overlay
    }

    fun uninstall(tree: JTree) {
        val overlay = tree.getClientProperty(INSTALLED) as? JComponent
        tree.putClientProperty(INSTALLED, null)
        CheckboxClicks.unregister(tree)
        if (overlay != null) {
            (overlay as? InclusionOverlay)?.let {
                tree.removeComponentListener(it.resizeListener)
                Disposer.dispose(it.disposable)
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

    /** Replaceable in tests; null when the file is not on disk (deleted), which is then never marked. */
    var stampOf: (FilePath) -> Stamp? = { path ->
        path.virtualFile?.let { file ->
            val document = if (ApplicationManager.getApplication().isDispatchThread) FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: -1 else -1
            Stamp(document, file.modificationStamp, file.timeStamp)
        }
    }

    fun pathOf(userObject: Any?): FilePath? = when (userObject) {
        is Change -> ChangesUtil.getFilePath(userObject)
        is FilePath -> userObject
        else -> null
    }

    class InclusionOverlay(private val tree: ChangesTree) : JComponent() {
        val disposable: Disposable = Disposer.newDisposable("BetterCodeReview.commitMarks")

        /** Content baseline of every included file, taken when it was ticked (or first seen ticked). */
        private val baselines = HashMap<FilePath, Stamp>()

        /** Files the developer clicked once to mark for another look; they stay included. */
        private val flagged = HashSet<FilePath>()

        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fitTree()
        }

        init {
            isOpaque = false
            isFocusable = false
            DecorationOwner.mark(this)
            fitTree()
            // Edits in editors and on disk (a tool writing files) change the stamps; a repaint re-reads them.
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = tree.repaint()
            }, disposable)
            ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) = tree.repaint()
            })
        }

        private fun fitTree() {
            bounds = Rectangle(0, 0, tree.width, tree.height)
        }

        override fun contains(x: Int, y: Int): Boolean = false

        /** Brings the baselines in line with what is ticked now: new ticks take a baseline, removed ticks drop it and the flag. */
        fun syncInclusion() {
            val included = tree.includedSet.mapNotNull { pathOf(it) }.toSet()
            baselines.keys.retainAll(included)
            flagged.retainAll(included)
            for (path in included) {
                if (path !in baselines) stampOf(path)?.let { baselines[path] = it }
            }
        }

        /** The dash shows on a ticked file that is flagged or whose content moved away from its baseline. */
        fun isMarked(path: FilePath): Boolean {
            if (path in flagged) return true
            val baseline = baselines[path] ?: return false
            val now = stampOf(path) ?: return false
            return now.changedSince(baseline)
        }

        fun isFlagged(path: FilePath): Boolean = path in flagged

        override fun paintComponent(g: Graphics) {
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
                if (!tree.isIncluded(userObject) || !isMarked(filePath)) continue
                val slot = RowSlots.stateSlot(tree, path, row) ?: continue
                RowSlots.paintDash(g, tree, row, slot, isCurrent = false)
            }
        }

        /** A press on the checkbox of a ticked, unmarked file flags it and keeps the platform from unticking it; everything else is the platform's. */
        fun decide(point: Point): CheckboxClicks.Decision? {
            val row = tree.getClosestRowForLocation(point.x, point.y).takeIf { it >= 0 } ?: return null
            val path = tree.getPathForRow(row) ?: return null
            if (!tree.model.isLeaf(path.lastPathComponent)) return null
            val userObject = TreeUtil.getLastUserObject(path) ?: return null
            val filePath = pathOf(userObject) ?: return null
            val slot = RowSlots.stateSlot(tree, path, row) ?: return null
            if (!slot.bounds.contains(point)) return null
            syncInclusion()
            if (!tree.isIncluded(userObject) || isMarked(filePath)) return null // the platform unticks a marked file, ticks an unticked one
            return CheckboxClicks.Decision(consume = true) {
                flagged.add(filePath)
                tree.repaint()
            }
        }
    }
}
