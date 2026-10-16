package pl.adrros.bettercodereview.review.files

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.actions.VcsContextFactory
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JTree

/**
 * The file open in the selected editor, kept per project so change trees can mark it while painting.
 * Compared as [FilePath], which knows about case-insensitive file systems.
 */
@Service(Service.Level.PROJECT)
class CurrentReviewFile(private val project: Project) {
    @Volatile
    var path: FilePath? = null
        private set

    private var initialised = false
    // A WeakHashMap-backed set: its iterator skips entries collected mid-iteration instead of throwing.
    private val trees: MutableSet<JTree> = Collections.newSetFromMap(WeakHashMap())

    fun update(file: VirtualFile?) = update(file?.let { VcsContextFactory.getInstance().createFilePathOn(it) })

    fun update(path: FilePath?) {
        initialised = true
        if (path == this.path) return
        this.path = path
        synchronized(trees) { trees.toList() }.forEach { it.repaint() }
    }

    fun track(tree: JTree) {
        synchronized(trees) { trees.add(tree) }
        if (!initialised) update(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }

    fun untrack(tree: JTree) {
        synchronized(trees) { trees.remove(tree) }
    }

    class EditorListener(private val project: Project) : FileEditorManagerListener {
        override fun selectionChanged(event: FileEditorManagerEvent) {
            // In a split layout the event's new file can be null although another split still shows one.
            project.service<CurrentReviewFile>().update(event.newFile ?: event.manager.selectedFiles.firstOrNull())
        }
    }
}
