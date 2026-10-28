package pl.adrros.bettercodereview.commit

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.components.service
import pl.adrros.bettercodereview.review.files.CheckboxClicks
import pl.adrros.bettercodereview.review.files.CurrentFileMark
import pl.adrros.bettercodereview.review.files.CurrentReviewFile
import pl.adrros.bettercodereview.review.files.RowSlots
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class CommitInclusionMarksTest : BasePlatformTestCase() {
    private class TestTree(project: com.intellij.openapi.project.Project) : ChangesTree(project, true, false) {
        override fun rebuildTree() {}
        fun show(model: DefaultTreeModel) = updateTreeModel(model)
    }

    private val stamps = HashMap<FilePath, CommitInclusionMarks.Stamp>()
    private lateinit var defaultStampOf: (FilePath) -> CommitInclusionMarks.Stamp?
    private var treeOrNull: TestTree? = null
    private val a = LocalFilePath("/repo/src/A.java", false)
    private val b = LocalFilePath("/repo/src/B.java", false)
    private lateinit var changeA: Change
    private lateinit var changeB: Change
    private lateinit var nodeA: ChangesBrowserNode<*>
    private lateinit var nodeB: ChangesBrowserNode<*>

    private fun revision(path: FilePath) = object : ContentRevision {
        override fun getContent(): String = ""
        override fun getFile(): FilePath = path
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    override fun setUp() {
        super.setUp()
        defaultStampOf = CommitInclusionMarks.stampOf
        CommitInclusionMarks.stampOf = { stamps[it] }
        stamps[a] = CommitInclusionMarks.Stamp(-1, 1, 100)
        stamps[b] = CommitInclusionMarks.Stamp(-1, 1, 100)
        changeA = Change(null, revision(a))
        changeB = Change(null, revision(b))
        nodeA = ChangesBrowserNode.createChange(project, changeA)
        nodeB = ChangesBrowserNode.createChange(project, changeB)
        val root = ChangesBrowserNode.createRoot()
        root.add(nodeA)
        root.add(nodeB)
        val tree = TestTree(project).also { treeOrNull = it }
        tree.show(DefaultTreeModel(root))
        tree.setSize(300, 120)
        tree.doLayout()
        tree.setIncludedChanges(listOf(changeA))
        CommitInclusionMarks.install(tree)
    }

    override fun tearDown() {
        try {
            treeOrNull?.let { CommitInclusionMarks.uninstall(it) }
            project.service<CurrentReviewFile>().update(null as FilePath?)
            if (::defaultStampOf.isInitialized) CommitInclusionMarks.stampOf = defaultStampOf
        } finally {
            super.tearDown()
        }
    }

    private val tree: TestTree get() = treeOrNull!!
    private val overlay get() = tree.components.filterIsInstance<CommitInclusionMarks.InclusionOverlay>().single()

    private fun render(): BufferedImage {
        val image = BufferedImage(tree.width, tree.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        tree.paint(g)
        g.dispose()
        return image
    }

    private fun dashPixels(image: BufferedImage, node: ChangesBrowserNode<*>): Int {
        val row = tree.getPathBounds(TreePath(node.path))!!
        val slot = RowSlots.stateSlot(tree, TreePath(node.path), tree.getRowForPath(TreePath(node.path)))!!.bounds
        val dash = RowSlots.DASH_IN_BOX.rgb and 0xFFFFFF
        return (slot.x until minOf(slot.x + slot.width, image.width)).sumOf { x -> (row.y until row.y + row.height).count { y -> (image.getRGB(x, y) and 0xFFFFFF) == dash } }
    }

    fun `test the inclusion checkbox is the slot and a ticked unchanged file has no dash`() {
        val slot = RowSlots.stateSlot(tree, TreePath(nodeA.path), 0)!!
        assertTrue("the platform's inclusion renderer puts the checkbox on the left", slot.isCheckbox && slot.bounds.x < tree.width / 2)
        assertEquals(true, slot.checked)
        val image = render()
        assertEquals(0, dashPixels(image, nodeA))
        assertEquals(0, dashPixels(image, nodeB))
    }

    /** B is unticked in setUp; clicking its checkbox is the platform's tick, remembered as a tick by hand. */
    private fun tickByClick(node: ChangesBrowserNode<*>, change: Change) {
        val decision = overlay.decide(slotCentre(node))
        assertNotNull("an unticked file is the platform's, but noted", decision)
        assertFalse(decision!!.consume)
        decision.onClick()
        tree.includeChange(change)
        overlay.syncInclusion()
    }

    fun `test only a file ticked by a click gets a baseline and dashes when modified or deleted`() {
        tickByClick(nodeB, changeB)
        stamps[a] = CommitInclusionMarks.Stamp(-1, 2, 100)
        stamps[b] = CommitInclusionMarks.Stamp(-1, 2, 100)
        val image = render()
        assertEquals("A was ticked before the plugin saw it: no baseline, no dash", 0, dashPixels(image, nodeA))
        assertTrue("B was ticked by a click and changed since", dashPixels(image, nodeB) > 0)
        stamps.remove(b)
        assertTrue("a deleted file is the biggest change", overlay.isMarked(b))
    }

    fun `test the document stamp counts only when both sides had a document`() {
        val base = CommitInclusionMarks.Stamp(-1, 1, 100)
        assertFalse(CommitInclusionMarks.Stamp(42, 1, 100).changedSince(base))
        assertTrue(CommitInclusionMarks.Stamp(43, 1, 100).changedSince(CommitInclusionMarks.Stamp(42, 1, 100)))
        assertTrue(CommitInclusionMarks.Stamp(-1, 1, 101).changedSince(base))
        assertTrue(CommitInclusionMarks.Stamp(-1, 2, 100).changedSince(base))
    }

    private fun slotCentre(node: ChangesBrowserNode<*>): Point {
        val path = TreePath(node.path)
        val bounds = RowSlots.stateSlot(tree, path, tree.getRowForPath(path))!!.bounds
        return Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
    }

    fun `test clicking a ticked file flags it and keeps it ticked, clicking the dash goes to the platform`() {
        render()
        val decision = overlay.decide(slotCentre(nodeA))
        assertNotNull("a ticked unmarked file is ours", decision)
        assertTrue(decision!!.consume)
        decision.onClick()
        assertTrue(tree.isIncluded(changeA))
        assertTrue(overlay.isFlagged(a))
        assertEquals("the consumed release skipped the tree's selection, so the decision selected the row", TreePath(nodeA.path), tree.selectionPath)
        assertTrue(dashPixels(render(), nodeA) > 0)
        assertNull("the dash click unticks through the platform", overlay.decide(slotCentre(nodeA)))
        assertNull("outside the checkbox nothing is ours", overlay.decide(Point(tree.width - 5, slotCentre(nodeA).y)))
    }

    fun `test unticking drops the flag and re-ticking takes a new baseline`() {
        render()
        overlay.decide(slotCentre(nodeA))!!.onClick()
        tree.setIncludedChanges(emptyList<Change>())
        overlay.syncInclusion()
        assertFalse(overlay.isFlagged(a))
        stamps[a] = CommitInclusionMarks.Stamp(-1, 5, 500)
        tickByClick(nodeA, changeA)
        assertFalse("ticked again with the current content as baseline", overlay.isMarked(a))
        stamps[a] = CommitInclusionMarks.Stamp(-1, 6, 600)
        assertTrue(overlay.isMarked(a))
    }

    fun `test a partially included file is the platform's alone`() {
        val platform = tree.inclusionModel
        tree.setInclusionModel(object : com.intellij.openapi.vcs.changes.InclusionModel by platform {
            override fun getInclusionState(item: Any): com.intellij.util.ui.ThreeStateCheckBox.State =
                if (item == changeA) com.intellij.util.ui.ThreeStateCheckBox.State.DONT_CARE else platform.getInclusionState(item)
        })
        tree.setIncludedChanges(listOf(changeA))
        render()
        assertNull("a partial inclusion toggles through the platform", overlay.decide(slotCentre(nodeA)))
        stamps[a] = CommitInclusionMarks.Stamp(-1, 9, 900)
        assertEquals("and never gets our dash", 0, dashPixels(render(), nodeA))
    }

    fun `test without checkboxes or when switched off nothing is ours`() {
        render()
        val at = slotCentre(nodeA) // without checkboxes the renderer has no slot at all
        tree.setShowCheckboxes(false)
        assertNull(overlay.decide(at))
        tree.setShowCheckboxes(true)
        CommitInclusionMarks.isEnabled = false
        try {
            assertNull(overlay.decide(at))
        } finally {
            CommitInclusionMarks.isEnabled = true
        }
        assertNotNull(overlay.decide(at))
    }

    fun `test uninstall removes the overlay and the click handler`() {
        render()
        val at = slotCentre(nodeA)
        fun pressAndRelease(): Boolean {
            CheckboxClicks.intercept(java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, at.x, at.y, 1, false, java.awt.event.MouseEvent.BUTTON1))
            val release = java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_RELEASED, 0, 0, at.x, at.y, 1, false, java.awt.event.MouseEvent.BUTTON1)
            CheckboxClicks.intercept(release)
            return release.isConsumed
        }
        assertTrue("before uninstall the release on a ticked file is ours", pressAndRelease())
        CommitInclusionMarks.uninstall(tree)
        assertEmpty(tree.components.filterIsInstance<CommitInclusionMarks.InclusionOverlay>())
        assertFalse(pressAndRelease())
        CommitInclusionMarks.install(tree)
    }

    private fun rgbAt(image: BufferedImage, x: Int, node: ChangesBrowserNode<*>): Int {
        val row = tree.getPathBounds(TreePath(node.path))!!
        return image.getRGB(x, row.y + row.height / 2) and 0xFFFFFF
    }

    fun `test the row of the file open in the editor gets the purple bar and tint, with or without checkboxes`() {
        val plainB = rgbAt(render(), tree.width / 2, nodeB)
        project.service<CurrentReviewFile>().update(b)
        val bar = CurrentFileMark.HIGHLIGHT.rgb and 0xFFFFFF
        for (checkboxes in listOf(true, false)) {
            tree.setShowCheckboxes(checkboxes)
            val image = render()
            assertEquals("bar at the left edge, checkboxes $checkboxes", bar, rgbAt(image, 1, nodeB))
            assertTrue("tint over the row, checkboxes $checkboxes", rgbAt(image, tree.width / 2, nodeB) != plainB)
            assertTrue("other row has no bar, checkboxes $checkboxes", rgbAt(image, 1, nodeA) != bar)
        }
        assertEquals(tree.getPathBounds(TreePath(nodeB.path))!!.y, overlay.markedRow!!.y)
        assertFalse("not showing, so no pulse", overlay.isPulsing)
    }

    fun `test the open file keeps its dash on top of the mark`() {
        tickByClick(nodeB, changeB)
        stamps[b] = CommitInclusionMarks.Stamp(-1, 2, 100)
        project.service<CurrentReviewFile>().update(b)
        assertTrue(dashPixels(render(), nodeB) > 0)
    }

    fun `test nothing is marked without a matching file`() {
        project.service<CurrentReviewFile>().update(LocalFilePath("/repo/src/Other.java", false))
        render()
        assertNull(overlay.markedRow)
        project.service<CurrentReviewFile>().update(null as FilePath?)
        render()
        assertNull(overlay.markedRow)
    }

    fun `test a folder leaf is never the open file`() {
        val folder = LocalFilePath("/repo/src/empty", true)
        val root = ChangesBrowserNode.createRoot()
        root.add(ChangesBrowserNode.createFilePath(folder))
        tree.show(DefaultTreeModel(root))
        project.service<CurrentReviewFile>().update(folder)
        render()
        assertNull(overlay.markedRow)
    }

    fun `test after uninstall the tree is no longer marked`() {
        CommitInclusionMarks.uninstall(tree)
        project.service<CurrentReviewFile>().update(b)
        render()
        assertEmpty(tree.components.filterIsInstance<CommitInclusionMarks.InclusionOverlay>())
        val reinstalled = CommitInclusionMarks.install(tree)
        render()
        assertNotNull("a fresh install picks the open file up again", reinstalled.markedRow)
    }
}
