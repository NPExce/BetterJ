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
    private val contents = HashMap<FilePath, String>()
    private lateinit var defaultContentOf: (FilePath) -> CharSequence?
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
        defaultContentOf = CommitInclusionMarks.contentOf
        CommitInclusionMarks.contentOf = { contents[it] }
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
            if (::defaultContentOf.isInitialized) CommitInclusionMarks.contentOf = defaultContentOf
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

    private lateinit var projectA: FilePath
    private lateinit var projectB: FilePath

    /** The Commit panel lists two files of the fixture's project, the first one ticked. */
    private fun withFilesOfProject(block: (com.intellij.openapi.vfs.VirtualFile, com.intellij.openapi.vfs.VirtualFile) -> Unit) {
        val fileA = myFixture.addFileToProject("src/A.java", "class A {}").virtualFile
        val fileB = myFixture.addFileToProject("src/B.java", "class B {}").virtualFile
        projectA = LocalFilePath(fileA.path, false)
        projectB = LocalFilePath(fileB.path, false)
        stamps[projectA] = CommitInclusionMarks.Stamp(-1, 1, 100)
        stamps[projectB] = CommitInclusionMarks.Stamp(-1, 1, 100)
        changeA = Change(null, revision(projectA))
        changeB = Change(null, revision(projectB))
        val root = ChangesBrowserNode.createRoot()
        val folder = ChangesBrowserNode.createFilePath(LocalFilePath(fileA.parent.path, true))
        root.add(folder)
        folder.add(ChangesBrowserNode.createChange(project, changeA))
        folder.add(ChangesBrowserNode.createChange(project, changeB))
        tree.show(DefaultTreeModel(root))
        tree.setIncludedChanges(listOf(changeA))
        block(fileA, fileB)
    }

    fun `test the project tree gets no checkboxes while the Commit panel shows none or the feature is off`() {
        assertSame(overlay, CommitInclusionMarks.overlayFor(project))
        CommitInclusionMarks.isEnabled = false
        try {
            assertNull(CommitInclusionMarks.overlayFor(project))
        } finally {
            CommitInclusionMarks.isEnabled = true
        }
        tree.isShowCheckboxes = false
        assertNull(CommitInclusionMarks.overlayFor(project))
    }

    fun `test the project tree's checkbox follows the commit and cycles like the Commit panel's`() = withFilesOfProject { fileA, fileB ->
        assertSame("the Commit panel shows checkboxes", overlay, CommitInclusionMarks.overlayFor(project))
        overlay.syncInclusion()
        assertEquals(CommitInclusionMarks.Tick.TICKED, overlay.tickOf(fileA))
        assertEquals(CommitInclusionMarks.Tick.NONE, overlay.tickOf(fileB))
        assertNull("an unchanged file has no checkbox", overlay.tickOf(myFixture.addFileToProject("src/C.java", "class C {}").virtualFile))

        overlay.clickFromProjectTree(fileB)
        assertEquals("ticked", CommitInclusionMarks.Tick.TICKED, overlay.tickOf(fileB))
        assertTrue("in the commit", tree.includedSet.contains(changeB))
        stamps[projectB] = CommitInclusionMarks.Stamp(-1, 2, 200)
        overlay.syncInclusion()
        assertEquals("changed since the tick", CommitInclusionMarks.Tick.MARKED, overlay.tickOf(fileB))
        overlay.clickFromProjectTree(fileB)
        assertEquals("a marked file is unticked", CommitInclusionMarks.Tick.NONE, overlay.tickOf(fileB))
        assertFalse(tree.includedSet.contains(changeB))

        overlay.clickFromProjectTree(fileA)
        assertEquals("a ticked file is marked for another look and stays in", CommitInclusionMarks.Tick.MARKED, overlay.tickOf(fileA))
        assertTrue(tree.includedSet.contains(changeA))
        assertTrue("the Commit panel shows the same dash", overlay.isFlagged(projectA))
    }

    fun `test git's numstat sums to the branch's lines, binary files as zero`() {
        assertEquals(ChangeLineStats.Lines(858, 8), BranchLineStats.parseNumstat(listOf("850\t6\tsrc/A.java", "8\t2\tsrc/{old => new}/B.java", "-\t-\tlogo.png")))
        assertEquals(ChangeLineStats.Lines(0, 0), BranchLineStats.parseNumstat(emptyList()))
    }

    fun `test the Commit panel shows the branch's lines against develop after its top row`() {
        val stats = project.service<BranchLineStats>()
        val defaults = Pair(stats.compute, stats.onComputed)
        var computed = 0
        try {
            stats.compute = { ChangeLineStats.Lines(858, 8) }
            stats.onComputed = { computed++ }
            render()
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("branch count", { computed > 0 }, 10)
            val image = render()
            val row = tree.getRowBounds(0)
            val after = (row.x + row.width until tree.width).flatMap { x -> (row.y until row.y + row.height).map { y -> java.awt.Color(image.getRGB(x, y)) } }
            assertTrue("green digits after the top row", after.any { it.green > it.red + 40 && it.green > it.blue + 20 })
            assertTrue("red digits after them", after.any { it.red > it.green + 40 && it.red > it.blue + 20 })
        } finally {
            stats.compute = defaults.first
            stats.onComputed = defaults.second
        }
    }

    fun `test a file edited back to the content it was ticked with loses the dash`() = withFilesOfProject { _, fileB ->
        contents[projectB] = "class B {}"
        overlay.syncInclusion()
        overlay.clickFromProjectTree(fileB)
        assertEquals(CommitInclusionMarks.Tick.TICKED, overlay.tickOf(fileB))

        stamps[projectB] = CommitInclusionMarks.Stamp(-1, 2, 200)
        contents[projectB] = "class B {}a"
        assertEquals("an \"a\" added", CommitInclusionMarks.Tick.MARKED, overlay.tickOf(fileB))

        stamps[projectB] = CommitInclusionMarks.Stamp(-1, 3, 300)
        contents[projectB] = "class B {}"
        assertEquals("the \"a\" removed again", CommitInclusionMarks.Tick.TICKED, overlay.tickOf(fileB))

        stamps[projectB] = CommitInclusionMarks.Stamp(-1, 4, 400)
        contents.remove(projectB)
        assertEquals("unreadable now counts as changed", CommitInclusionMarks.Tick.MARKED, overlay.tickOf(fileB))
    }

    fun `test Commit (Cmd+K) keeps the checkboxes set by hand`() = withFilesOfProject { _, _ ->
        val keeper = KeepInclusionOnCommit()
        keeper.before(KeepInclusionOnCommit.COMMIT, project)
        tree.inclusionModel.addInclusion(listOf(changeB)) // what the platform does on Commit
        keeper.after(KeepInclusionOnCommit.COMMIT)
        assertEquals("only the file ticked before", setOf<Any>(changeA), tree.includedSet.toSet())

        keeper.before(KeepInclusionOnCommit.COMMIT, project)
        tree.inclusionModel.clearInclusion()
        tree.inclusionModel.addInclusion(listOf(changeB))
        keeper.after(KeepInclusionOnCommit.COMMIT)
        assertEquals("a reset to other files is undone too", setOf<Any>(changeA), tree.includedSet.toSet())

        keeper.before("CheckinFiles", project)
        tree.inclusionModel.addInclusion(listOf(changeB))
        keeper.after("CheckinFiles")
        assertTrue("committing a chosen file is the platform's", tree.includedSet.contains(changeB))

        tree.inclusionModel.removeInclusion(listOf(changeB))
        tree.isShowCheckboxes = false
        keeper.before(KeepInclusionOnCommit.COMMIT, project)
        tree.isShowCheckboxes = true
        tree.inclusionModel.addInclusion(listOf(changeB))
        keeper.after(KeepInclusionOnCommit.COMMIT)
        assertEquals("kept also when the panel showed no checkboxes before", setOf<Any>(changeA), tree.includedSet.toSet())

        CommitInclusionMarks.isEnabled = false
        try {
            keeper.before(KeepInclusionOnCommit.COMMIT, project)
            tree.inclusionModel.addInclusion(listOf(changeB))
            keeper.after(KeepInclusionOnCommit.COMMIT)
            assertTrue("the feature off leaves Commit alone", tree.includedSet.contains(changeB))
        } finally {
            CommitInclusionMarks.isEnabled = true
        }
    }

    fun `test the project tree draws the checkbox before a changed file's icon and takes its clicks`() = withFilesOfProject { fileA, _ ->
        val psiManager = com.intellij.psi.PsiManager.getInstance(project)
        val settings = com.intellij.ide.projectView.ViewSettings.DEFAULT
        val folder = javax.swing.tree.DefaultMutableTreeNode(com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode(project, psiManager.findDirectory(fileA.parent)!!, settings))
        val file = javax.swing.tree.DefaultMutableTreeNode(com.intellij.ide.projectView.impl.nodes.PsiFileNode(project, psiManager.findFile(fileA)!!, settings))
        folder.add(file)
        val projectTree = com.intellij.ui.treeStructure.Tree(DefaultTreeModel(folder)).apply {
            setSize(300, 80)
            expandRow(0)
            doLayout()
        }
        val defaultCommitOf = ProjectTreeTicks.commitOf
        ProjectTreeTicks.commitOf = { overlay }
        val ticks = ProjectTreeTicks.install(projectTree)
        try {
            val path = TreePath(file.path)
            assertNotNull("a file under a folder", ticks.fileOf(path))
            assertNull("the folder itself", ticks.fileOf(TreePath(folder.path)))
            val member = javax.swing.tree.DefaultMutableTreeNode(com.intellij.ide.projectView.impl.nodes.PsiFileNode(project, psiManager.findFile(fileA)!!, settings))
            file.add(member)
            assertNull("a node inside a node of the same file", ticks.fileOf(TreePath(member.path)))
            file.remove(member)
            val row = projectTree.getRowForPath(path)
            val box = ticks.boxOf(row, path)!!
            assertTrue("before the icon", box.x + box.width <= projectTree.getRowBounds(row).x)

            val image = BufferedImage(projectTree.width, projectTree.height, BufferedImage.TYPE_INT_RGB)
            projectTree.paint(image.createGraphics())
            val background = image.getRGB(1, projectTree.height - 1)
            assertTrue("the checkbox is drawn", (box.x until box.x + box.width).any { x -> image.getRGB(x, box.y + box.height / 2) != background })

            val decision = ticks.decide(Point(box.x + box.width / 2, box.y + box.height / 2))!!
            assertTrue("the platform does not see the click", decision.consume)
            decision.onClick()
            assertEquals(CommitInclusionMarks.Tick.MARKED, overlay.tickOf(fileA))
            val rowBounds = projectTree.getRowBounds(row)
            assertNotNull("the box's left edge", ticks.decide(Point(box.x, box.y + box.height / 2)))
            assertNotNull("between the box and the icon", ticks.decide(Point(rowBounds.x - 1, box.y + box.height / 2)))
            assertNotNull("the row's top edge above the box", ticks.decide(Point(box.x + box.width / 2, rowBounds.y)))
            assertNull("a press on the file name is the tree's", ticks.decide(Point(rowBounds.x + 5, box.y + box.height / 2)))
        } finally {
            ProjectTreeTicks.uninstall(projectTree)
            ProjectTreeTicks.commitOf = defaultCommitOf
        }
    }
}
