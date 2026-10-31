package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.util.ui.tree.TreeUtil
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestFileViewedState
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import pl.adrros.bettercodereview.settings.Feature

private val LOG = logger<ViewedStateCycle>()

/**
 * Turns the two-state "viewed" checkbox of the pull request tree into a cycle: a ticked file clicked once shows a
 * dash ("read it again"), clicked again becomes unticked, clicked again is ticked. A file GitHub itself dismissed
 * after new commits shows the same dash and follows the same path. GitHub stores only ticked or not: the dash is
 * the plugin's own flag ([ReviewFlags]) on top of an unticked file. Whether a file is ticked is read from the
 * checkbox the renderer shows, which is what the platform's own toggle reads too, so both decide from one state.
 */
object ViewedStateCycle {
    // 2026.1 names the listener installViewedStateToggleHandler$1, 2026.3 installRendererComponentClickHandler$1 (also used for jump-to-source, harmless to wrap).
    private const val NATIVE_TOGGLE = "com.intellij.collaboration.ui.codereview.CodeReviewProgressTreeModelKt\$install"
    var isEnabled: Boolean
        get() = Feature.VIEWED_CYCLE.isOn
        set(value) {
            Feature.VIEWED_CYCLE.isOn = value
        }

    enum class Step {
        /** Let the platform untick the file, and flag it: the row shows the dash. */
        FLAG_AND_UNTICK,
        /** The dash was clicked: take the flag away and make sure the file is unticked; the platform must not tick it. */
        CLEAR_TO_UNTICKED,
        /** Nothing of ours: the platform ticks the file. */
        NATIVE,
    }

    fun step(ticked: Boolean, dismissed: Boolean, flagged: Boolean): Step = when {
        flagged -> Step.CLEAR_TO_UNTICKED
        ticked -> Step.FLAG_AND_UNTICK
        dismissed -> Step.CLEAR_TO_UNTICKED
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

    /** Installs the cycle when the platform's toggle is where the plugin expects it; otherwise the click would tick the file anyway. */
    fun install(tree: ChangesTree, overlay: ReviewChangeTrees.CurrentFileOverlay): Boolean {
        val listeners = tree.mouseListeners
        if (listeners.none { it.javaClass.name.startsWith(NATIVE_TOGGLE) }) {
            LOG.warn("Viewed checkbox cycle off: the platform's viewed-state toggle was not found among ${listeners.size} mouse listener(s): ${listeners.map { it.javaClass.name }}")
            return false
        }
        // Rewrap in place, so the listener order the platform set up stays as it was.
        listeners.forEach(tree::removeMouseListener)
        listeners.forEach { tree.addMouseListener(if (it.javaClass.name.startsWith(NATIVE_TOGGLE)) ConsumedAwareListener(it) else it) }
        CheckboxClicks.register(tree) { point -> decide(tree, overlay, point) }
        return true
    }

    fun uninstall(tree: ChangesTree) {
        CheckboxClicks.unregister(tree)
        val listeners = tree.mouseListeners
        if (listeners.none { it is ConsumedAwareListener }) return
        listeners.forEach(tree::removeMouseListener)
        listeners.forEach { tree.addMouseListener((it as? ConsumedAwareListener)?.delegate ?: it) }
    }

    private fun decide(tree: ChangesTree, overlay: ReviewChangeTrees.CurrentFileOverlay, point: Point): CheckboxClicks.Decision? {
        if (!isEnabled) return null
        val row = tree.getClosestRowForLocation(point.x, point.y).takeIf { it >= 0 } ?: return null
        val path = tree.getPathForRow(row) ?: return null
        if (!tree.model.isLeaf(path.lastPathComponent)) return null
        val change = TreeUtil.getLastUserObject(path) as? RefComparisonChange ?: return null
        val nodePath = ReviewChangeTrees.pathOf(change) ?: return null
        val relative = overlay.relativePath(nodePath) ?: return null
        val slot = RowSlots.stateSlot(tree, path, row) ?: return null
        if (!slot.bounds.contains(point)) return null
        val ticked = slot.checked == true
        val dismissed = overlay.viewedStateOf(relative) == GHPullRequestFileViewedState.DISMISSED
        // The platform's toggle acts on the click, so the decision does too; each outcome also records what the
        // file is about to be, so a quick next click sees it before GitHub answers.
        val click = CheckboxClicks.Trigger.CLICK
        return when (step(ticked, dismissed, overlay.isFlagged(relative))) {
            Step.NATIVE -> CheckboxClicks.Decision(consume = false, click) { overlay.assume(relative, GHPullRequestFileViewedState.VIEWED) }
            Step.FLAG_AND_UNTICK -> CheckboxClicks.Decision(consume = false, click) {
                overlay.setFlag(relative, true)
                overlay.assume(relative, GHPullRequestFileViewedState.UNVIEWED)
            }
            Step.CLEAR_TO_UNTICKED -> CheckboxClicks.Decision(consume = true, click) {
                overlay.setFlag(relative, false)
                if (ticked || dismissed) untick(tree, change)
                overlay.assume(relative, GHPullRequestFileViewedState.UNVIEWED)
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

    class ToggleCycleAction : ToggleAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = isEnabled
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            isEnabled = state
            ReviewChangeTrees.repaintAll()
        }
    }
}
