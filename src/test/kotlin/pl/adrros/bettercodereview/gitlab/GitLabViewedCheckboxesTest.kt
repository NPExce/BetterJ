package pl.adrros.bettercodereview.gitlab

import com.intellij.collaboration.ui.codereview.CodeReviewProgressTreeModelFromDetails
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeDetails
import com.intellij.collaboration.ui.codereview.setupCodeReviewProgressModel
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.model.GitLabMergeRequestChangeListViewModel
import pl.adrros.bettercodereview.review.files.RowSlots
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** The merge request's tree as the GitLab plugin of 2026.1 and 2026.2 builds it: no viewed checkboxes of its own. */
class GitLabViewedCheckboxesTest : BasePlatformTestCase() {
    private val revision = object : ShortVcsRevisionNumber {
        override fun toShortString(): String = "abc"
        override fun asString(): String = "abcdef"
        override fun compareTo(other: VcsRevisionNumber): Int = 0
    }

    private class TestTree(project: com.intellij.openapi.project.Project) : ChangesTree(project, false, false) {
        override fun rebuildTree() {}
        fun show(model: DefaultTreeModel) = updateTreeModel(model)
    }

    private val change by lazy { RefComparisonChange(revision, null, revision, VcsUtil.getFilePath("/repo/src/A.java", false)) }
    private val details = MutableStateFlow<Map<RefComparisonChange, CodeReviewChangeDetails>>(emptyMap())
    private val setCalls = mutableListOf<Pair<List<RefComparisonChange>, Boolean>>()

    private fun gitLabModel(): GitLabMergeRequestChangeListViewModel {
        val type = GitLabMergeRequestChangeListViewModel::class.java
        return java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            when (method.name) {
                "isOnLatest" -> true
                "getDetailsByChange" -> details
                "getChanges" -> listOf(change)
                "getProject" -> project
                "setViewedState" -> {
                    @Suppress("UNCHECKED_CAST")
                    setCalls += (args[0] as Iterable<RefComparisonChange>).toList() to args[1] as Boolean
                    Unit
                }
                "toString" -> "fake GitLab change list"
                "hashCode" -> 1
                "equals" -> false
                else -> throw UnsupportedOperationException(method.name)
            }
        } as GitLabMergeRequestChangeListViewModel
    }

    private fun toggles(tree: ChangesTree) = tree.mouseListeners.filter { it.javaClass.name.contains("installViewedStateToggleHandler") }

    fun `test the GitLab tree gets the IDE's own viewed checkboxes, and clicking a ticked one unticks the file`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            details.value = mapOf(change to CodeReviewChangeDetails(true, 0)) // a read file: the checkbox shows without hovering
            val tree = TestTree(project)
            tree.setUI(com.intellij.ui.tree.ui.DefaultTreeUI()) // the IDE's tree UI; headless tests get the look-and-feel one
            val leaf = object : ChangesBrowserNode<RefComparisonChange>(change) {
                override fun getTextPresentation(): String = "A.java"
            }
            val root = ChangesBrowserNode.createRoot().apply { add(leaf) }
            tree.show(DefaultTreeModel(root))
            val model = gitLabModel()
            tree.setupCodeReviewProgressModel(model, CodeReviewProgressTreeModelFromDetails(scope, model))
            tree.setBounds(0, 0, 600, 200)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val path = TreePath(arrayOf(root, leaf))
            val row = tree.getRowForPath(path)
            assertTrue(toggles(tree).isEmpty())
            assertFalse("2026.1 draws no checkbox", RowSlots.stateSlot(tree, path, row)?.isCheckbox == true)

            val remove = GitLabTreeBackend().addViewedCheckboxes(tree, model, scope)
            assertNotNull(remove)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(1, toggles(tree).size)
            val slot = RowSlots.stateSlot(tree, path, row)
            assertTrue("the row shows a ticked checkbox", slot?.isCheckbox == true && slot.checked == true)

            val at = slot!!.bounds
            val click = MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, at.centerX.toInt(), at.centerY.toInt(), 1, false, MouseEvent.BUTTON1)
            toggles(tree).single().mouseClicked(click)
            assertEquals(listOf(listOf(change) to false), setCalls)

            remove!!()
            assertTrue(toggles(tree).isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
