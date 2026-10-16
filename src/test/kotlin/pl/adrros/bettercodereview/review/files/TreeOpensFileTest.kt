package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
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
    private val hints = mutableListOf<String>()
    private var treeOrNull: TestTree? = null
    private lateinit var fileNode: ChangesBrowserNode<*>
    private lateinit var deletedNode: ChangesBrowserNode<*>
    private lateinit var folderNode: ChangesBrowserNode<*>
    private lateinit var folderFileNode: ChangesBrowserNode<*>
    private var branch = TreeOpensFile.BranchState.CHECKED_OUT
    private var tempDir: File? = null
    private lateinit var defaultBranchLookup: (javax.swing.JTree) -> TreeOpensFile.BranchState
    private lateinit var defaultHint: (ChangesTree, String) -> Unit

    override fun setUp() {
        super.setUp()
        defaultBranchLookup = TreeOpensFile.pullRequestBranch
        defaultHint = TreeOpensFile.showHint
        TreeOpensFile.pullRequestBranch = { branch }
        TreeOpensFile.showHint = { _, text -> hints += text }
        TreeOpensFile.isEnabled = true
        // Files on disk: the production lookup goes through the local file system, which in-memory test files bypass.
        val dir = FileUtil.createTempDirectory("tree-opens-file", null).also { tempDir = it }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir.resolve("A.java").apply { writeText("class A {}") })!!
        val nested = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir.resolve("src").apply { mkdirs() }.resolve("B.java").apply { writeText("class B {}") })!!
        fileNode = node(RefComparisonChange(revision, null, revision, LocalFilePath(file.path, false)))
        deletedNode = node(RefComparisonChange(revision, LocalFilePath(file.path + ".old", false), revision, null))
        folderFileNode = node(RefComparisonChange(revision, null, revision, LocalFilePath(nested.path, false)))
        folderNode = ChangesBrowserNode.createFilePath(LocalFilePath(dir.resolve("src").path, true)).apply { add(folderFileNode) }
        val root = ChangesBrowserNode.createRoot()
        root.add(fileNode)
        root.add(deletedNode)
        root.add(folderNode)
        val tree = TestTree(project).also { treeOrNull = it }
        tree.show(DefaultTreeModel(root))
        tree.doubleClickHandler = Processor { originalDoubleClicks += it; true }
        tree.enterKeyHandler = Processor { originalEnters += it; true }
        tree.setSize(300, 200)
        tree.expandPath(TreePath(folderNode.path))
        ReviewChangeTrees.install(tree, project.service<CurrentReviewFile>())
    }

    private fun node(change: RefComparisonChange) = object : ChangesBrowserNode<RefComparisonChange>(change) {
        override fun getTextPresentation(): String = (change.filePathAfter ?: change.filePathBefore)?.name ?: "?"
    }

    override fun tearDown() {
        try {
            treeOrNull?.let { ReviewChangeTrees.uninstall(it) }
            if (::defaultBranchLookup.isInitialized) TreeOpensFile.pullRequestBranch = defaultBranchLookup
            if (::defaultHint.isInitialized) TreeOpensFile.showHint = defaultHint
            TreeOpensFile.isEnabled = true
            val editors = FileEditorManager.getInstance(project)
            editors.openFiles.forEach { editors.closeFile(it) }
        } finally {
            try {
                tempDir?.let { FileUtil.delete(it) }
            } finally {
                super.tearDown()
            }
        }
    }

    private val tree: TestTree get() = treeOrNull!!

    private fun select(vararg nodes: ChangesBrowserNode<*>) {
        tree.selectionPaths = nodes.map { TreePath(it.path) }.toTypedArray()
    }

    private fun doubleClick(modifiers: Int = 0) =
        tree.doubleClickHandler!!.process(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, modifiers, 10, 10, 2, false, MouseEvent.BUTTON1))

    private fun enter() = tree.enterKeyHandler!!.process(KeyEvent(tree, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n'))

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

    fun `test Enter dispatched through the tree's own key listener reaches the wrapper`() {
        select(fileNode)
        tree.keyListeners.forEach { it.keyPressed(KeyEvent(tree, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n')) }
        assertEquals(listOf("A.java"), openFiles)
        assertEmpty(originalEnters)
    }

    fun `test Alt+double-click goes to the original diff handler`() {
        select(fileNode)
        doubleClick(InputEvent.ALT_DOWN_MASK)
        assertEquals(1, originalDoubleClicks.size)
        assertEmpty(openFiles)
    }

    fun `test the setting turns the feature off`() {
        TreeOpensFile.isEnabled = false
        select(fileNode)
        doubleClick()
        enter()
        assertEquals(1, originalDoubleClicks.size)
        assertEquals(1, originalEnters.size)
        assertEmpty(openFiles)
    }

    fun `test a folder with one file, a deleted file, a multi-selection and no selection fall back to the diff`() {
        select(folderNode)
        doubleClick()
        select(deletedNode)
        doubleClick()
        select(fileNode, folderFileNode)
        doubleClick()
        tree.clearSelection()
        doubleClick()
        assertEquals(4, originalDoubleClicks.size)
        assertEmpty(openFiles)
        assertEmpty(hints)
    }

    fun `test another branch checked out falls back to the diff with one hint per tree`() {
        branch = TreeOpensFile.BranchState.OTHER
        select(fileNode)
        doubleClick()
        enter()
        assertEquals(1, originalDoubleClicks.size)
        assertEquals(1, originalEnters.size)
        assertEquals(1, hints.size)
        assertTrue(hints.single().contains("Double-Click Opens File"))
        assertEmpty(openFiles)
    }

    fun `test a tree without a GitHub pull request falls back silently`() {
        branch = TreeOpensFile.BranchState.UNKNOWN
        select(fileNode)
        doubleClick()
        assertEquals(1, originalDoubleClicks.size)
        assertEmpty(hints)
        assertEmpty(openFiles)
    }

    fun `test uninstall restores both original handlers and a stale wrapper is unwrapped on reinstall`() {
        val wrappedDoubleClick = tree.doubleClickHandler
        val wrappedEnter = tree.enterKeyHandler
        ReviewChangeTrees.uninstall(tree)
        assertNotSame(wrappedDoubleClick, tree.doubleClickHandler)
        assertNotSame(wrappedEnter, tree.enterKeyHandler)
        select(fileNode)
        tree.doubleClickHandler!!.process(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, 10, 10, 2, false, MouseEvent.BUTTON1))
        tree.enterKeyHandler!!.process(KeyEvent(tree, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n'))
        assertEquals(1, originalDoubleClicks.size)
        assertEquals(1, originalEnters.size)
        assertEmpty(openFiles)

        // A version unloaded without restart leaves its wrapper in place and the originals in client properties.
        val first = TreeOpensFile.install(tree)
        val second = TreeOpensFile.install(tree)
        assertSame(first.originalDoubleClick, second.originalDoubleClick)
        assertSame(first.originalEnter, second.originalEnter)
        TreeOpensFile.uninstall(tree, second)
        assertSame(first.originalDoubleClick, tree.doubleClickHandler)
        ReviewChangeTrees.install(tree, project.service<CurrentReviewFile>())
    }
}
