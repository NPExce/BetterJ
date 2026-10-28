package pl.adrros.bettercodereview.colors

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import java.awt.Color

/** The colour of a file's package, for the project tree, the change trees (which ask for the same colour) and the editor tabs. */
class PackageColorProvider : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? = project.service<PackageColors>().colorOf(file)

    override fun getProjectViewColor(project: Project, file: VirtualFile): Color? = project.service<PackageColors>().colorOf(file)
}

/** Repaints everything that shows the colours once they change. */
class PackageColorsRefresher : PackageColors.Listener {
    override fun colorsChanged(project: Project) {
        if (project.isDisposed) return
        ProjectView.getInstance(project).refresh()
        val editors = FileEditorManagerEx.getInstanceEx(project)
        editors.openFiles.forEach(editors::updateFilePresentation)
        WindowManager.getInstance().getFrame(project)?.repaint()
        PackageLines.refreshAll(project)
    }
}
