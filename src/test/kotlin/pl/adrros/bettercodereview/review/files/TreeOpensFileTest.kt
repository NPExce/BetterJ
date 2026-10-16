package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class TreeOpensFileTest : BasePlatformTestCase() {
    private val revision = object : ShortVcsRevisionNumber {
        override fun toShortString(): String = "abc"
        override fun asString(): String = "abcdef"
        override fun compareTo(other: VcsRevisionNumber): Int = 0
    }

    private class TestTree(project: com.intellij.openapi.project.Project) : ChangesTree(project, false, false) {
        override fun rebuildTree() {}
        fun show(model: DefaultTreeModel) = updateTreeModel(model)
    }

    private val originalDoubleClicks = mutableListOf<MouseEvent>()
    private val originalEnters = mutableListOf<KeyEvent>()
    private lateinit var tree: TestTree
    private lateinit var fileNode: ChangesBrowserNode<*>
    private lateinit var deletedNode: ChangesBrowserNode<*>
    private var branchCheckedOut = true
    private lateinit var tempDir: java.io.File

    override fun setUp() {
        super.setUp()
        TreeOpensFile.pullRequestBranchCheckedOut = { branchCheckedOut }
        TreeOpensFile.isEnabled = true
        // A file on disk: the production lookup goes through the local file system, which the in-memory test files bypass.
        tempDir = com.intellij.openapi.util.io.FileUtil.createTempDirectory("tree-opens-file", null)
        val onDisk = tempDir.resolve("A.java").apply { writeText("class A {}") }
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
        val change = RefComparisonChange(revision, null, revision, LocalFilePath(file.path, false))
        val deleted = RefComparisonChange(revision, LocalFilePath(file.path + ".old", false), revision, null)
        fileNode = node(change)
        deletedNode = node(deleted)
        val root = ChangesBrowserNode.createRoot()
        root.add(fileNode)
        root.add(deletedNode)
        tree = TestTree(project)
        tree.show(DefaultTreeModel(root))
        tree.doubleClickHandler = Processor { originalDoubleClicks += it; true }
        tree.enterKeyHandler = Processor { originalEnters += it; true }
        tree.setSize(300, 200)
        ReviewChangeTrees.install(tree, project.service<CurrentReviewFile>())
    }

    private fun node(change: RefComparisonChange) = object : ChangesBrowserNode<RefComparisonChange>(change) {
        override fun getTextPresentation(): String = (change.filePathAfter ?: change.filePathBefore)?.name ?: "?"
    }

    override fun tearDown() {
        try {
            ReviewChangeTrees.uninstall(tree)
            TreeOpensFile.pullRequestBranchCheckedOut = { false }
            TreeOpensFile.isEnabled = true
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
            com.intellij.openapi.util.io.FileUtil.delete(tempDir)
        } finally {
            super.tearDown()
        }
    }

    private fun select(node: ChangesBrowserNode<*>) {
        tree.selectionPath = TreePath(node.path)
    }

    private fun doubleClick(modifiers: Int = 0) =
        tree.doubleClickHandler!!.process(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, modifiers, 10, 10, 2, false, MouseEvent.BUTTON1))

    private fun enter(modifiers: Int = 0) =
        tree.enterKeyHandler!!.process(KeyEvent(tree, KeyEvent.KEY_PRESSED, 0, modifiers, KeyEvent.VK_ENTER, '\n'))

    private val openFiles get() = FileEditorManager.getInstance(project).openFiles.map { it.name }

    fun `test double-click and Enter open the selected file in the editor`() {
        select(fileNode)
        assertTrue(doubleClick())
        assertEquals(listOf("A.java"), openFiles)
        assertEmpty(originalDoubleClicks)

        FileEditorManager.getInstance(project).closeFile(FileEditorManager.getInstance(project).openFiles.single())
        assertTrue(enter())
        assertEquals(listOf("A.java"), openFiles)
        assertEmpty(originalEnters)
    }

    fun `test Alt+double-click and Shift+Enter go to the original diff handler`() {
        select(fileNode)
        doubleClick(InputEvent.ALT_DOWN_MASK)
        enter(InputEvent.SHIFT_DOWN_MASK)
        assertEquals(1, originalDoubleClicks.size)
        assertEquals(1, originalEnters.size)
        assertEmpty(openFiles)
    }

    fun `test the setting turns the feature off`() {
        TreeOpensFile.isEnabled = false
        select(fileNode)
        doubleClick()
        assertEquals(1, originalDoubleClicks.size)
        assertEmpty(openFiles)
    }

    fun `test a deleted file, no selection and a pull request branch that is not checked out fall back to the diff`() {
        select(deletedNode)
        doubleClick()
        tree.clearSelection()
        doubleClick()
        branchCheckedOut = false
        select(fileNode)
        doubleClick()
        assertEquals(3, originalDoubleClicks.size)
        assertEmpty(openFiles)
    }

    fun `test uninstall restores the original handlers`() {
        val wrappedDoubleClick = tree.doubleClickHandler
        ReviewChangeTrees.uninstall(tree)
        assertNotSame(wrappedDoubleClick, tree.doubleClickHandler)
        select(fileNode)
        tree.doubleClickHandler!!.process(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, 10, 10, 2, false, MouseEvent.BUTTON1))
        assertEquals(1, originalDoubleClicks.size)
        assertEmpty(openFiles)
        ReviewChangeTrees.install(tree, project.service<CurrentReviewFile>())
    }
}
