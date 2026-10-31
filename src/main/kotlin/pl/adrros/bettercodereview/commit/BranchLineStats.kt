package pl.adrros.bettercodereview.commit

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Graphics
import java.awt.Graphics2D
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JTree
import pl.adrros.bettercodereview.settings.Feature

private val LOG = logger<BranchLineStats>()

/**
 * Lines added and removed on the whole branch: from where it left its base up to the files on disk, so its commits
 * and the work not committed yet. The base is the branch it grew from: develop, or the previous branch of a stack.
 * Read with git, so unsaved edits and files not yet added to git are left out.
 * Shown on the Commit panel's top row, next to the change list's own total, and also when nothing is changed locally.
 */
@Service(Service.Level.PROJECT)
class BranchLineStats(private val project: Project, private val scope: CoroutineScope) {
    @Volatile
    private var total: Total? = null

    @Volatile
    private var computedFor: Any? = null
    private val running = AtomicBoolean()

    /** Replaceable in tests; runs on a background thread. Null when no repository has a base or HEAD is on develop itself. */
    var compute: suspend () -> Total? = { withGit() }

    /** Called when a count arrives; repaints the Commit panel. */
    var onComputed: () -> Unit = { CommitInclusionMarks.repaintAll() }

    /** The last count, recounted in the background once [signature] (the Commit panel's model, a minute) moves on. */
    fun totalFor(signature: Any): Total? {
        if (signature != computedFor && running.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                try {
                    delay(DEBOUNCE_MS)
                    total = compute()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    LOG.info("Could not count the branch's lines against develop", e)
                } finally {
                    computedFor = signature
                    running.set(false)
                }
                withContext(Dispatchers.EDT) { onComputed() }
            }
        }
        return total
    }

    private suspend fun withGit(): Total? {
        val bases = mutableListOf<String>()
        var lines: ChangeLineStats.Lines? = null
        for (root in readAction { gitRoots() }) {
            val current = git(root, GitCommand.REV_PARSE, "--abbrev-ref", "HEAD")?.firstOrNull() ?: continue
            if (current == BASE) continue
            val base = nearestBase(root, current) ?: continue
            val mergeBase = git(root, GitCommand.MERGE_BASE, base.ref, "HEAD")?.firstOrNull()?.trim() ?: continue
            val numstat = git(root, GitCommand.DIFF, "--numstat", mergeBase) ?: continue
            lines = (lines ?: ChangeLineStats.Lines(0, 0)) + parseNumstat(numstat)
            bases += base.name
        }
        return lines?.let { Total(bases.distinct().joinToString(", "), it) }
    }

    /** The branch HEAD grew from, or develop when git is too old to rank branches (before 2.41). */
    private fun nearestBase(root: VirtualFile, current: String): Base? {
        val refs = git(root, GitCommand.FOR_EACH_REF, "--format=$RANK_FORMAT", "refs/heads", "refs/remotes/origin")
        if (refs != null) return pickBase(refs, current)
        val ref = BASE_REFS.firstOrNull { git(root, GitCommand.REV_PARSE, "--verify", "--quiet", it) != null } ?: return null
        return Base(ref, BASE)
    }

    /**
     * The git repositories holding the project's content: the nearest folder with a `.git` (a folder, or a file in a
     * worktree) at or above each content root. Found on disk because the VCS API sits in a module of its own since 2026.3.
     */
    private fun gitRoots(): List<VirtualFile> = ProjectRootManager.getInstance(project).contentRoots
        .mapNotNull { root -> generateSequence(root) { it.parent }.firstOrNull { it.findChild(".git") != null } }
        .distinct()

    /** The command's output lines, or null when it fails (a missing branch, no common history). */
    private fun git(root: VirtualFile, command: GitCommand, vararg parameters: String): List<String>? {
        val handler = GitLineHandler(project, root, command)
        handler.setSilent(true)
        handler.addParameters(*parameters)
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.output.filter { it.isNotBlank() } else null
    }

    /** "vs develop +858 −8" (or the stack's previous branch) right after the tree's top row, or at its top left while the tree is empty. */
    fun paint(g: Graphics, tree: JTree) {
        if (!Feature.LINE_COUNTS.isOn) return
        // The Commit panel rebuilds its model after every refresh of the local changes, a commit and a checkout included.
        val (base, total) = totalFor(listOf(System.identityHashCode(tree.model), System.currentTimeMillis() / REFRESH_MS)) ?: return
        if (total.added == 0 && total.deleted == 0) return
        val g2 = g.create() as Graphics2D
        try {
            UISettings.setupAntialiasing(g2)
            g2.font = tree.font
            val metrics = g2.fontMetrics
            val top = tree.getRowBounds(0)
            var x = if (top != null) top.x + top.width + metrics.stringWidth("  ") else tree.visibleRect.x + JBUI.scale(8)
            val y = if (top != null) top.y + (top.height - metrics.height) / 2 + metrics.ascent else tree.visibleRect.y + JBUI.scale(4) + metrics.ascent
            // On the focused selection the row's own foreground keeps the text readable.
            val onSelection = top != null && tree.isRowSelected(0) && tree.hasFocus()
            val parts = listOf(
                "vs $base " to SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor,
                "+${total.added}" to ChangeLineStats.ADDED,
                " −${total.deleted}" to ChangeLineStats.DELETED,
            )
            for ((text, color) in parts) {
                g2.color = if (onSelection) UIUtil.getTreeSelectionForeground(true) else color
                g2.drawString(text, x, y)
                x += metrics.stringWidth(text)
            }
        } finally {
            g2.dispose()
        }
    }

    /** The branch total and the base it is counted from. */
    data class Total(val base: String, val lines: ChangeLineStats.Lines)

    /** A base as git names it ([ref]) and as the panel shows it ([name], without `origin/`). */
    data class Base(val ref: String, val name: String)

    companion object {
        const val BASE = "develop"
        private const val REMOTE = "origin/"

        /** Per branch: commits it lacks of HEAD's, the branch, and the target of a symbolic ref such as origin/HEAD. */
        private const val RANK_FORMAT = "%(ahead-behind:HEAD)\t%(refname:short)\t%(symref)"

        /**
         * The branch with the fewest of HEAD's commits missing, from `git for-each-ref` lines in [RANK_FORMAT]: the one
         * HEAD grew from. A stacked branch gets the branch below it, any other one develop. Ties go to develop, then to
         * the remote copy. HEAD's own branch, its remote copy, symbolic refs and branches holding HEAD (the stack's upper
         * branches) are skipped; develop holding HEAD is kept, it is the base of a branch with no commits yet.
         */
        fun pickBase(lines: List<String>, current: String): Base? = lines.mapNotNull { line ->
            val parts = line.split('\t')
            val behind = parts.getOrNull(0)?.split(' ')?.getOrNull(1)?.toIntOrNull()
            val ref = parts.getOrNull(1).orEmpty()
            val name = ref.removePrefix(REMOTE)
            if (behind == null || (behind == 0 && name != BASE) || ref.isEmpty() || parts.getOrNull(2).orEmpty().isNotEmpty() || name == current) null
            else Triple(behind, ref, name)
        }.minWithOrNull(compareBy({ it.first }, { it.third != BASE }, { !it.second.startsWith(REMOTE) }))
            ?.let { Base(it.second, it.third) }

        /** The remote branch first: it moves with every fetch, while a local develop is often left behind. */
        private val BASE_REFS = listOf("origin/$BASE", BASE)
        private const val DEBOUNCE_MS = 500L

        /** Recounts at least this often, so a commit made outside the IDE shows up. */
        private const val REFRESH_MS = 60_000L

        /** Sums `git diff --numstat` lines ("added<TAB>deleted<TAB>path"); binary files ("-<TAB>-") count as zero. */
        fun parseNumstat(lines: List<String>): ChangeLineStats.Lines = lines.fold(ChangeLineStats.Lines(0, 0)) { sum, line ->
            val parts = line.split('\t')
            val added = parts.getOrNull(0)?.toIntOrNull()
            val deleted = parts.getOrNull(1)?.toIntOrNull()
            if (added == null || deleted == null) sum else sum + ChangeLineStats.Lines(added, deleted)
        }
    }
}
