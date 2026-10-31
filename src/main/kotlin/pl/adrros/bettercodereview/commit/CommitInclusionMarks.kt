package pl.adrros.bettercodereview.commit

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.InclusionListener
import com.intellij.openapi.vcs.changes.InclusionModel
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.ui.ThreeStateCheckBox
import com.intellij.util.ui.tree.TreeUtil
import pl.adrros.bettercodereview.review.DecorationOwner
import com.intellij.openapi.components.service
import pl.adrros.bettercodereview.review.files.CheckboxClicks
import pl.adrros.bettercodereview.review.files.CurrentFileMark
import pl.adrros.bettercodereview.review.files.CurrentReviewFile
import pl.adrros.bettercodereview.review.files.RowSlots
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.TreePath
import pl.adrros.bettercodereview.settings.Feature

private val LOG = com.intellij.openapi.diagnostic.logger<CommitInclusionMarks>()

/** How long after the Commit panel appears the saved ticks are put back over the IDE's own "tick everything". */
private const val RESTORE_WINDOW_MS = 30_000L

/**
 * The Commit tool window's checkboxes say which files go into the commit. The plugin adds a third look to them:
 * a dash on a file ticked by hand whose content differs from what it was at the tick (edited by the developer, or
 * written on disk by a tool; edited back, it loses the dash again), and on a ticked file clicked once, which marks it for another look while keeping it in the commit. The
 * next click unticks it as usual, and ticking it again takes the file's current content as the new baseline.
 * Only files ticked by a click get a baseline: files the IDE ticks on its own, or that were ticked before the
 * plugin saw the tree, would otherwise show the dash on every keystroke.
 * The file open in the editor gets the same pulsing purple mark as in the pull request's tree.
 */
object CommitInclusionMarks {
    const val INSTALLED = "BetterCodeReview.commitMarks"

    var isEnabled: Boolean
        get() = Feature.COMMIT_MARKS.isOn
        set(value) {
            Feature.COMMIT_MARKS.isOn = value
        }

    private val installed: MutableSet<JTree> = Collections.newSetFromMap(WeakHashMap())

    /** Every local-changes tree is watched; whether it shows checkboxes is checked when painting and clicking, since the Commit panel turns them on later. */
    fun watch(tree: ChangesListView) {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) return }
        install(tree)
    }

    fun install(tree: ChangesTree, currentFile: CurrentReviewFile = tree.project.service()): InclusionOverlay {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) uninstall(tree) else tree.remove(it) }
        val overlay = InclusionOverlay(tree, currentFile)
        tree.putClientProperty(INSTALLED, overlay)
        tree.add(overlay)
        tree.addComponentListener(overlay.resizeListener)
        synchronized(installed) { installed.add(tree) }
        currentFile.track(tree)
        CheckboxClicks.register(tree) { point -> overlay.decide(point) }
        overlay.startListening()
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
                it.currentFile.untrack(tree)
            }
            tree.remove(overlay)
        }
        synchronized(installed) { installed.remove(tree) }
        tree.repaint()
    }

    fun uninstallAll() {
        synchronized(installed) { installed.toList() }.forEach { tree -> runCatching { uninstall(tree) } }
    }

    fun repaintAll() {
        synchronized(installed) { installed.toList() }.forEach { it.repaint() }
    }

    /** The project's Commit panel while it shows checkboxes: the owner of what the project tree's checkboxes show. */
    fun overlayFor(project: Project): InclusionOverlay? =
        synchronized(installed) { installed.toList() }
            .filter { it is ChangesTree && it.project === project }
            .firstNotNullOfOrNull { (it.getClientProperty(INSTALLED) as? InclusionOverlay)?.takeIf(InclusionOverlay::isActive) }

    /** The project's Commit panel, also while it shows no checkboxes: Cmd+K resets its inclusion either way. */
    fun anyOverlayFor(project: Project): InclusionOverlay? =
        overlayFor(project) ?: synchronized(installed) { installed.toList() }
            .filter { it is ChangesTree && it.project === project }
            .firstNotNullOfOrNull { it.getClientProperty(INSTALLED) as? InclusionOverlay }

    /** What a commit includes at a moment, with the model it was read from. */
    class Inclusion internal constructor(internal val model: InclusionModel, internal val items: Set<Any>)

    /** How a file stands in the commit: left out, ticked, ticked with only some lines, or ticked and marked for another look. */
    enum class Tick { NONE, TICKED, PARTIAL, MARKED }

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

    /** Replaceable in tests; the file's text as the editor sees it (unsaved edits included), null for a binary or missing file. */
    var contentOf: (FilePath) -> CharSequence? = { path ->
        path.virtualFile?.takeIf { it.isValid && !it.isDirectory && !it.fileType.isBinary }?.let { file ->
            ReadAction.compute<CharSequence?, RuntimeException> {
                FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence ?: LoadTextUtil.loadText(file)
            }
        }
    }

    private fun digestOf(path: FilePath): ByteArray? = contentOf(path)?.let { digestOf(it.toString()) }

    private fun digestOf(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    /** Longer texts keep only their digest: they still dash, but show no diff. */
    private const val KEPT_TEXT_CHARS = 2_000_000

    /**
     * The content a file was ticked with: its stamps, to skip the comparison while nothing was written, a digest of its
     * text, and the text itself for the diff since the tick (null after a restart or for a very long file).
     */
    private class Baseline(val stamp: Stamp, val digest: ByteArray?, val text: String? = null)

    /** The stamp of a baseline saved before a restart: any stamp differs from it, so the digest decides. */
    private val UNKNOWN_STAMP = Stamp(-2, -2, -2)

    /** The file a node stands for: a change by its path after the change (before it, when deleted); ChangesUtil moves between IDE versions, so the revisions are read directly. */
    fun pathOf(userObject: Any?): FilePath? = when (userObject) {
        is Change -> userObject.afterRevision?.file ?: userObject.beforeRevision?.file
        is FilePath -> userObject
        else -> null
    }

    class InclusionOverlay(private val tree: ChangesTree, val currentFile: CurrentReviewFile) : JComponent() {
        private val mark = CurrentFileMark(this)

        val isPulsing: Boolean get() = mark.isPulsing

        /** The current file's row as of the last paint, for tests. */
        internal val markedRow: Rectangle? get() = mark.markedRow

        /** Content baseline of every file ticked by a click, taken right after the tick. */
        private val baselines = HashMap<FilePath, Baseline>()

        /** The last comparison with the baseline per file, valid while the file's stamps stay the same. */
        private val verdicts = HashMap<FilePath, Pair<Stamp, Boolean>>()

        /** Files whose tick came from a click on the checkbox; only they get a baseline. */
        private val tickedByClick = HashSet<FilePath>()

        /** Files the developer clicked once more to mark for another look; they stay included. */
        private val flagged = HashSet<FilePath>()

        private var listening: Disposable? = null

        private val inclusionListener = object : InclusionListener {
            override fun inclusionChanged() {
                restoreTicks()
                saveTicks()
                ProjectTreeTicks.repaintAll()
            }
        }

        private val savedTicks: CommitTicks get() = tree.project.service()

        /** The ticks saved before the restart, put back until the developer clicks a checkbox or the window passes. */
        private var pendingTicks: Map<String, CommitTicks.Tick>? = null
        private var pendingUntil = 0L
        private var restoring = false
        private var baselinesRestored = false

        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fitTree()
        }

        init {
            isOpaque = false
            isFocusable = false
            DecorationOwner.mark(this)
            fitTree()
        }

        /**
         * The listeners live as long as the overlay is installed, or until the project closes: the project tree keeps
         * showing the commit's checkboxes while the Commit panel is hidden and out of the component hierarchy.
         */
        fun startListening() {
            if (listening != null) return
            val disposable = Disposer.newDisposable("BetterCodeReview.commitMarks").also { listening = it }
            Disposer.register(tree.project, disposable)
            if (CommitInclusionMarks.isEnabled) {
                pendingTicks = savedTicks.ticks?.associateBy { it.path }
                pendingUntil = System.currentTimeMillis() + RESTORE_WINDOW_MS
            }
            val modelListener = java.beans.PropertyChangeListener {
                items = null
                restoreTicks()
                ProjectTreeTicks.repaintAll()
            }
            tree.addPropertyChangeListener(JTree.TREE_MODEL_PROPERTY, modelListener)
            Disposer.register(disposable) { tree.removePropertyChangeListener(JTree.TREE_MODEL_PROPERTY, modelListener) }
            tree.inclusionModel.addInclusionListener(inclusionListener)
            Disposer.register(disposable) { tree.inclusionModel.removeInclusionListener(inclusionListener) }
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
            restoreTicks()
        }

        override fun removeNotify() {
            mark.stop()
            super.removeNotify()
        }

        fun stopListening() {
            mark.stop()
            listening?.let(Disposer::dispose)
            listening = null
        }

        private fun repaintIfShowing() {
            if (tree.isShowing) tree.repaint()
            ProjectTreeTicks.repaintAll()
        }

        private fun fitTree() {
            bounds = Rectangle(0, 0, tree.width, tree.height)
        }

        override fun contains(x: Int, y: Int): Boolean = false

        val isActive: Boolean get() = CommitInclusionMarks.isEnabled && tree.isShowCheckboxes // qualified: JComponent has its own isEnabled

        /** Fully ticked, not the platform's own "[-]" of a file with only some lines included. */
        private fun isTicked(userObject: Any): Boolean = tree.inclusionModel.getInclusionState(userObject) == ThreeStateCheckBox.State.SELECTED

        /** The developer took over: the saved ticks no longer override the IDE's. */
        private fun endRestoring() {
            pendingTicks = null
        }

        /** Unticks what was not ticked before the restart and ticks what was, with the content each tick was given. */
        private fun restoreTicks() {
            val pending = pendingTicks ?: return
            if (restoring) return
            if (System.currentTimeMillis() > pendingUntil) return endRestoring()
            val all = itemsByPath()
            if (all.isEmpty()) return
            val model = tree.inclusionModel
            val drop = all.filterKeys { it !in pending }.values.filter { model.getInclusionState(it) != ThreeStateCheckBox.State.NOT_SELECTED }
            val keep = all.filterKeys { it in pending }.values.filter { model.getInclusionState(it) != ThreeStateCheckBox.State.SELECTED }
            if (!baselinesRestored) {
                baselinesRestored = true
                for ((path, item) in all) {
                    val tick = pending[path] ?: continue
                    val filePath = pathOf(item) ?: continue
                    tickedByClick.add(filePath)
                    if (tick.flagged) flagged.add(filePath)
                    if (tick.digest.isNotEmpty()) baselines[filePath] = Baseline(UNKNOWN_STAMP, java.util.HexFormat.of().parseHex(tick.digest))
                }
                LOG.info("Commit ticks: put back ${pending.size} saved tick(s) over ${all.size} change(s)")
            }
            if (drop.isEmpty() && keep.isEmpty()) return
            restoring = true
            try {
                if (drop.isNotEmpty()) model.removeInclusion(drop)
                if (keep.isNotEmpty()) model.addInclusion(keep)
            } finally {
                restoring = false
            }
        }

        /** Keeps the ticks for the next start, once the saved ones are back in place. */
        private fun saveTicks() {
            if (pendingTicks != null || restoring || !CommitInclusionMarks.isEnabled || items == null && itemsByPath().isEmpty()) return
            val hex = java.util.HexFormat.of()
            val ticked = tree.includedSet.filter { isTicked(it) }.mapNotNull { pathOf(it) }
            savedTicks.save(ticked.map { CommitTicks.Tick(it.path, baselines[it]?.digest?.let(hex::formatHex).orEmpty(), it in flagged) })
        }

        private fun itemsByPath(): Map<String, Any> {
            if (items == null) {
                items = (tree.model.root as? ChangesBrowserNode<*>)?.traverseObjectsUnder()
                    ?.filter { pathOf(it)?.isDirectory == false }
                    ?.toList()?.associateBy { pathOf(it)!!.path }
                    .orEmpty()
            }
            return items.orEmpty()
        }

        /** Brings the baselines in line with what is ticked now: clicked ticks take a baseline, removed ticks drop it and the flag. */
        fun syncInclusion() {
            if (items == null) {
                items = (tree.model.root as? ChangesBrowserNode<*>)?.traverseObjectsUnder()
                    ?.filter { pathOf(it)?.isDirectory == false }
                    ?.toList()?.associateBy { pathOf(it)!!.path }
                    .orEmpty()
            }
            val included = tree.includedSet.filter { isTicked(it) }.mapNotNull { pathOf(it) }.toSet()
            baselines.keys.retainAll(included)
            verdicts.keys.retainAll(included)
            flagged.retainAll(included)
            tickedByClick.retainAll(included)
            for (path in tickedByClick) {
                if (path !in baselines) stampOf(path)?.let { stamp ->
                    val text = contentOf(path)?.toString()
                    baselines[path] = Baseline(stamp, text?.let(::digestOf), text?.takeIf { it.length <= KEPT_TEXT_CHARS })
                }
            }
            saveTicks()
        }

        /** The dash shows on a ticked file that is flagged or whose content differs from its baseline (a deletion counts). */
        fun isMarked(path: FilePath): Boolean {
            if (path in flagged) return true
            val baseline = baselines[path] ?: return false
            val now = stampOf(path) ?: return true
            if (!now.changedSince(baseline.stamp)) return false
            verdicts[path]?.takeIf { it.first == now }?.let { return it.second }
            val changed = baseline.digest == null || digestOf(path)?.contentEquals(baseline.digest) != true
            verdicts[path] = now to changed
            return changed
        }

        fun isFlagged(path: FilePath): Boolean = path in flagged

        /** The text [path] had when it was ticked, while the commit still includes it; null when it was not kept. */
        fun tickedText(path: String): String? = baselines.entries.firstOrNull { it.key.path == path }?.value?.text

        /** The current-file mark shows with or without checkboxes; the dashes only while the Commit panel shows checkboxes. */
        override fun paintComponent(g: Graphics) {
            tree.project.service<BranchLineStats>().paint(g, tree)
            val dashes = isActive
            val current = currentFile.path
            if (!dashes && current == null) {
                mark.painted(null)
                return
            }
            val clip = g.clipBounds
            if (dashes && !mark.isPulseRepaint(clip)) syncInclusion()
            var marked: Rectangle? = null
            val visible = tree.visibleRect
            if (tree.rowCount > 0 && !visible.isEmpty) {
                val first = tree.getClosestRowForLocation(visible.x, visible.y).coerceAtLeast(0)
                val last = tree.getClosestRowForLocation(visible.x, visible.y + visible.height - 1)
                for (row in first..last) {
                    val path = tree.getPathForRow(row) ?: continue
                    val bounds = tree.getRowBounds(row) ?: continue
                    if (!tree.model.isLeaf(path.lastPathComponent)) continue
                    val userObject = TreeUtil.getLastUserObject(path) ?: continue
                    val filePath = pathOf(userObject) ?: continue
                    val rowRect = Rectangle(visible.x, bounds.y, visible.width, bounds.height)
                    val isCurrent = current != null && !filePath.isDirectory && filePath == current && Feature.CURRENT_FILE.isOn
                    if (isCurrent) marked = rowRect // remembered even outside the clip, so a partial repaint keeps the pulse alive
                    if (clip != null && !clip.intersects(rowRect)) continue
                    if (isCurrent) mark.paint(g, bounds, visible)
                    if (!dashes || !isTicked(userObject) || !isMarked(filePath)) continue
                    val slot = RowSlots.stateSlot(tree, path, row) ?: continue
                    RowSlots.paintDash(g, tree, row, slot, isCurrent)
                }
            }
            mark.painted(marked)
        }

        /**
         * A press on the checkbox of a ticked, unmarked file flags it and keeps the platform from unticking it. A press
         * on an unticked file is the platform's, but remembered as a tick by hand. Everything else is the platform's.
         */
        fun decide(point: Point): CheckboxClicks.Decision? {
            if (!isActive) return null
            val row = tree.getClosestRowForLocation(point.x, point.y).takeIf { it >= 0 } ?: return null
            val path = tree.getPathForRow(row) ?: return null
            if (!tree.model.isLeaf(path.lastPathComponent)) return null
            val userObject = TreeUtil.getLastUserObject(path) ?: return null
            val filePath = pathOf(userObject) ?: return null
            val slot = RowSlots.stateSlot(tree, path, row) ?: return null
            if (!slot.bounds.contains(point)) return null
            endRestoring()
            syncInclusion()
            val state = tree.inclusionModel.getInclusionState(userObject)
            return when {
                state == ThreeStateCheckBox.State.NOT_SELECTED -> CheckboxClicks.Decision(consume = false) {
                    tickedByClick.add(filePath)
                    tree.repaint()
                }
                state == ThreeStateCheckBox.State.SELECTED && !isMarked(filePath) -> CheckboxClicks.Decision(consume = true) {
                    // The consumed release skips the tree UI's own selection and focus handling.
                    tree.selectionPath = path
                    tree.requestFocusInWindow()
                    flagged.add(filePath)
                    tree.repaint()
                    ProjectTreeTicks.repaintAll()
                }
                else -> null // the platform unticks a marked file; a partially included file is the platform's alone
            }
        }

        /** Every change and new file the Commit panel lists, by path; folders of its grouping are left out. Rebuilt by [syncInclusion] after the tree's model changes. */
        private var items: Map<String, Any>? = null

        private fun inclusionItemOf(file: VirtualFile): Any? = items?.get(file.path)

        /** How [file] stands in the commit, as of the last [syncInclusion]; null when the Commit panel has no checkbox for it. */
        fun tickOf(file: VirtualFile): Tick? {
            val item = inclusionItemOf(file) ?: return null
            return when (tree.inclusionModel.getInclusionState(item)) {
                ThreeStateCheckBox.State.NOT_SELECTED -> Tick.NONE
                ThreeStateCheckBox.State.DONT_CARE -> Tick.PARTIAL
                ThreeStateCheckBox.State.SELECTED -> if (pathOf(item)?.let(::isMarked) == true) Tick.MARKED else Tick.TICKED
            }
        }

        fun inclusion(): Inclusion = Inclusion(tree.inclusionModel, tree.inclusionModel.getInclusion().toSet())

        /** Puts [before] back by undoing only what changed since, so a file with some lines left out keeps them out. */
        fun restore(before: Inclusion) {
            val model = tree.inclusionModel
            if (model !== before.model) return
            val now = model.getInclusion()
            val added = now.filter { it !in before.items }
            val dropped = before.items.filter { it !in now }
            if (added.isNotEmpty()) model.removeInclusion(added)
            if (dropped.isNotEmpty()) model.addInclusion(dropped)
        }

        /** A click on the project tree's checkbox, with the Commit panel's cycle: tick (taking a baseline), mark, untick. */
        fun clickFromProjectTree(file: VirtualFile) {
            endRestoring()
            syncInclusion()
            val item = inclusionItemOf(file) ?: return
            val path = pathOf(item) ?: return
            when (tickOf(file)) {
                Tick.TICKED -> flagged.add(path)
                Tick.MARKED -> tree.inclusionModel.removeInclusion(listOf(item))
                Tick.NONE, Tick.PARTIAL -> {
                    tree.inclusionModel.addInclusion(listOf(item))
                    tickedByClick.add(path)
                }
                null -> return
            }
            syncInclusion()
            tree.repaint()
            ProjectTreeTicks.repaintAll()
        }
    }

    class ToggleMarksAction : ToggleAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = isEnabled
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            isEnabled = state
            repaintAll()
            ProjectTreeTicks.repaintAll()
        }
    }
}
