package pl.adrros.betterpullrequests.review.files

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.containers.ContainerUtil
import javax.swing.JTree

/** The file open in the selected editor, kept per project so change trees can mark it while painting. */
@Service(Service.Level.PROJECT)
class CurrentReviewFile(private val project: Project) {
    @Volatile
    var path: String? = null
        private set

    private val trees = ContainerUtil.createWeakSet<JTree>()

    init {
        update(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }

    fun update(file: VirtualFile?) {
        val newPath = file?.path
        if (newPath == path) return
        path = newPath
        synchronized(trees) { trees.toList() }.forEach { it.repaint() }
    }

    fun track(tree: JTree) {
        synchronized(trees) { trees.add(tree) }
    }

    class EditorListener(private val project: Project) : FileEditorManagerListener {
        override fun selectionChanged(event: FileEditorManagerEvent) {
            project.service<CurrentReviewFile>().update(event.newFile)
        }
    }
}
