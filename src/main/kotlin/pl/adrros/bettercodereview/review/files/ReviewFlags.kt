package pl.adrros.bettercodereview.review.files

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Files the reviewer flagged as "read it again" by clicking a ticked "viewed" checkbox: GitHub has no such state
 * (it only knows viewed, not viewed, and dismissed by new commits), so the plugin keeps it per project, keyed by
 * pull request and repository-relative path, and survives IDE restarts through the project's properties.
 * Entries are kept in insertion order and the oldest go when the set outgrows [LIMIT], since nothing tells the
 * plugin when a pull request is gone.
 */
@Service(Service.Level.PROJECT)
class ReviewFlags(private val project: Project) {
    private val flags: LinkedHashSet<String> = load()
    private val listeners = mutableListOf<() -> Unit>()

    fun isFlagged(pullRequest: String, path: String): Boolean = synchronized(flags) { key(pullRequest, path) in flags }

    fun set(pullRequest: String, path: String) = change {
        val added = flags.add(key(pullRequest, path))
        while (flags.size > LIMIT) flags.remove(flags.first())
        added
    }

    fun clear(pullRequest: String, path: String) = change { flags.remove(key(pullRequest, path)) }

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun change(edit: () -> Boolean) {
        val changed = synchronized(flags) {
            val result = edit()
            if (result) PropertiesComponent.getInstance(project).setValue(KEY, flags.joinToString(ENTRY_SEPARATOR))
            result
        }
        if (changed) synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    private fun load(): LinkedHashSet<String> =
        PropertiesComponent.getInstance(project).getValue(KEY)?.split(ENTRY_SEPARATOR)?.filterTo(LinkedHashSet()) { it.isNotEmpty() } ?: LinkedHashSet()

    // Tab and newline: printable in XML, unlike control characters, and absent from any sane path.
    private fun key(pullRequest: String, path: String) = "$pullRequest\t$path"

    companion object {
        const val KEY = "pl.adrros.bettercodereview.rereadFlags"
        const val LIMIT = 500
        private const val ENTRY_SEPARATOR = "\n"
    }
}
