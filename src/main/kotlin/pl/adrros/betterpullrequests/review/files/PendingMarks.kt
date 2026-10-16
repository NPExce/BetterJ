package pl.adrros.betterpullrequests.review.files

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.actions.VcsContextFactory

/**
 * How many of the viewer's not-yet-submitted review comments sit in each changed file: new threads and pending
 * replies alike, one count per comment.
 */
class PendingMarks(private val byFile: Map<FilePath, Int>) {
    val isEmpty: Boolean get() = byFile.isEmpty()

    fun countFor(file: FilePath): Int = byFile[file] ?: 0

    /** The total for a folder, computed from the files the tree shows beneath it, like the platform's own badge. */
    fun sumFor(files: Iterable<FilePath>): Int = files.sumOf { countFor(it) }

    /** Fallback for a directory path when the tree node does not list its files. */
    fun sumUnder(directory: FilePath): Int = byFile.entries.filter { it.key.isUnder(directory, false) }.sumOf { it.value }

    companion object {
        val NONE = PendingMarks(emptyMap())

        /** @param pendingCommentPaths repository-relative path of every pending comment, one entry per comment. */
        fun of(repositoryRootPath: String, pendingCommentPaths: Collection<String>): PendingMarks {
            val factory = VcsContextFactory.getInstance()
            return PendingMarks(pendingCommentPaths.groupingBy { factory.createFilePath("$repositoryRootPath/$it", false) }.eachCount())
        }
    }
}
