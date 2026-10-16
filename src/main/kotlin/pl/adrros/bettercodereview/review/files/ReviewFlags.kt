package pl.adrros.bettercodereview.review.files

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Files the reviewer flagged as "read it again" by clicking a ticked "viewed" checkbox: GitHub has no such state
 * (it only knows viewed, not viewed, and dismissed by new commits), so the plugin keeps it per project, keyed by
 * pull request and repository-relative path, and survives IDE restarts through the project's properties.
 */
@Service(Service.Level.PROJECT)
class ReviewFlags(private val project: Project) {
    private val flags: MutableSet<String> = load()
    private val listeners = mutableListOf<() -> Unit>()

    fun isFlagged(pullRequest: String, path: String): Boolean = synchronized(flags) { key(pullRequest, path) in flags }

    fun set(pullRequest: String, path: String) = change { flags.add(key(pullRequest, path)) }

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
            if (result) PropertiesComponent.getInstance(project).setValue(KEY, flags.joinToString("\n"))
            result
        }
        if (changed) synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    private fun load(): MutableSet<String> =
        PropertiesComponent.getInstance(project).getValue(KEY)?.split('\n')?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()

    private fun key(pullRequest: String, path: String) = "$pullRequest\u0000$path"

    companion object {
        const val KEY = "pl.adrros.bettercodereview.rereadFlags"
    }
}
