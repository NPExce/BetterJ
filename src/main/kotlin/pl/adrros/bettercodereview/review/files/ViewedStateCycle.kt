package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.util.ui.tree.TreeUtil
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestFileViewedState
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.event.MouseListener

private val LOG = logger<ViewedStateCycle>()

/**
 * Turns the two-state "viewed" checkbox of the pull request tree into a cycle: a ticked file clicked once shows a
 * dash ("read it again"), clicked again becomes unticked, clicked again is ticked. A file GitHub itself dismissed
 * after new commits shows the same dash and follows the same path. GitHub stores only ticked or not: the dash is
 * the plugin's own flag ([ReviewFlags]) on top of an unticked file.
 */
object ViewedStateCycle {
    private const val NATIVE_TOGGLE = "com.intellij.collaboration.ui.codereview.CodeReviewProgressTreeModelKt\$installViewedStateToggleHandler"

    enum class Step {
        /** Let the platform untick the file, and flag it: the row shows the dash. */
        FLAG_AND_UNTICK,
        /** The dash was clicked: take the flag away and make sure the file is unticked; the platform must not tick it. */
        CLEAR_TO_UNTICKED,
        /** Nothing of ours: the platform ticks the file. */
        NATIVE,
    }

    fun step(state: GHPullRequestFileViewedState?, flagged: Boolean): Step = when {
        flagged -> Step.CLEAR_TO_UNTICKED
        state == GHPullRequestFileViewedState.VIEWED -> Step.FLAG_AND_UNTICK
        state == GHPullRequestFileViewedState.DISMISSED -> Step.CLEAR_TO_UNTICKED
        else -> Step.NATIVE
    }

    /** The platform's toggle ignores consumed events; it is rewrapped so a click taken by the cycle does not reach it. */
    class ConsumedAwareListener(val delegate: MouseListener) : MouseListener {
        override fun mouseClicked(e: MouseEvent) { if (!e.isConsumed) delegate.mouseClicked(e) }
        override fun mousePressed(e: MouseEvent) { if (!e.isConsumed) delegate.mousePressed(e) }
        override fun mouseReleased(e: MouseEvent) { if (!e.isConsumed) delegate.mouseReleased(e) }
        override fun mouseEntered(e: MouseEvent) = delegate.mouseEntered(e)
        override fun mouseExited(e: MouseEvent) = delegate.mouseExited(e)
    }

    fun install(tree: ChangesTree, overlay: ReviewChangeTrees.CurrentFileOverlay) {
        for (listener in tree.mouseListeners) {
            if (listener.javaClass.name.startsWith(NATIVE_TOGGLE)) {
                tree.removeMouseListener(listener)
                tree.addMouseListener(ConsumedAwareListener(listener))
            }
        }
        CheckboxClicks.register(tree) { point -> decide(tree, overlay, point) }
    }

    fun uninstall(tree: ChangesTree) {
        CheckboxClicks.unregister(tree)
        for (listener in tree.mouseListeners) {
            if (listener is ConsumedAwareListener) {
                tree.removeMouseListener(listener)
                tree.addMouseListener(listener.delegate)
            }
        }
    }

    private fun decide(tree: ChangesTree, overlay: ReviewChangeTrees.CurrentFileOverlay, point: Point): CheckboxClicks.Decision? {
        val row = tree.getClosestRowForLocation(point.x, point.y).takeIf { it >= 0 } ?: return null
        val path = tree.getPathForRow(row) ?: return null
        if (!tree.model.isLeaf(path.lastPathComponent)) return null
        val change = TreeUtil.getLastUserObject(path) as? RefComparisonChange ?: return null
        val nodePath = ReviewChangeTrees.pathOf(change) ?: return null
        val relative = overlay.relativePath(nodePath) ?: return null
        val slot = RowSlots.stateSlot(tree, path, row) ?: return null
        if (!slot.bounds.contains(point)) return null
        val state = overlay.viewedStateOf(relative)
        return when (step(state, overlay.isFlagged(relative))) {
            Step.NATIVE -> null
            Step.FLAG_AND_UNTICK -> CheckboxClicks.Decision(consume = false) { overlay.setFlag(relative, true) }
            Step.CLEAR_TO_UNTICKED -> CheckboxClicks.Decision(consume = true) {
                overlay.setFlag(relative, false)
                if (state != GHPullRequestFileViewedState.UNVIEWED) untick(tree, change)
            }
        }
    }

    private fun untick(tree: ChangesTree, change: RefComparisonChange) {
        val model = ReviewChangeTrees.changeListModelOf(tree) as? CodeReviewChangeListViewModel.WithViewedState
        if (model == null) {
            LOG.info("Cannot untick ${change.filePathAfter ?: change.filePathBefore}: the change list model does not expose the viewed state")
            return
        }
        model.setViewedState(listOf(change), false)
    }
}
