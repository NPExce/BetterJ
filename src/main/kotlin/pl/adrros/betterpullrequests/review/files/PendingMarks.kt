package pl.adrros.betterpullrequests.review.files

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.actions.VcsContextFactory

/**
 * How many of the viewer's not-yet-submitted review comments sit in each changed file. A directory counts
 * everything beneath it, so a collapsed folder still shows where unsent work hides.
 */
class PendingMarks(private val byFile: Map<FilePath, Int>) {
    val isEmpty: Boolean get() = byFile.isEmpty()

    fun countFor(node: FilePath): Int =
        if (node.isDirectory) byFile.entries.filter { it.key.isUnder(node, false) }.sumOf { it.value }
        else byFile[node] ?: 0

    companion object {
        val NONE = PendingMarks(emptyMap())

        /** @param pendingThreadPaths repository-relative path of every pending thread, one entry per thread. */
        fun of(repositoryRootPath: String, pendingThreadPaths: Collection<String>): PendingMarks {
            val factory = VcsContextFactory.getInstance()
            val counts = pendingThreadPaths.groupingBy { factory.createFilePath("$repositoryRootPath/$it", false) }.eachCount()
            return PendingMarks(counts)
        }
    }
}
