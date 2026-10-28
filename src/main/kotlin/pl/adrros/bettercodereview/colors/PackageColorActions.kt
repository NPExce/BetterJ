package pl.adrros.bettercodereview.colors

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.ColorIcon
import pl.adrros.bettercodereview.BetterCodeReviewBundle

/** "Package Color" in the project tree's and the editor tab's context menus: one entry per palette colour, then clear and edit. */
class PackageColorActionGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && filesOf(e).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val colors = project.service<PackageColors>().colors
        return (colors.map { AssignColorAction(it) } + Separator.getInstance() + ClearColorAction() + EditColorsAction()).toTypedArray()
    }

    companion object {
        fun filesOf(e: AnActionEvent): List<VirtualFile> {
            val project = e.project ?: return emptyList()
            val base = project.basePath ?: return emptyList()
            val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
            return files.filter { it.isInLocalFileSystem && PackageColors.relativePath(base, it.path) != null }
        }
    }
}

private class AssignColorAction(private val color: PackageColors.PaletteColor) : DumbAwareAction(color.name, null, ColorIcon(12, color.color)) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<PackageColors>().assign(PackageColorActionGroup.filesOf(e), color.id)
    }
}

private class ClearColorAction : DumbAwareAction(BetterCodeReviewBundle.message("packageColors.clear"), null, AllIcons.Actions.Cancel) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val colors = project?.service<PackageColors>()
        e.presentation.isEnabled = colors != null && PackageColorActionGroup.filesOf(e).any { colors.assignedColorId(it) != null }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<PackageColors>().assign(PackageColorActionGroup.filesOf(e), null)
    }
}

private class EditColorsAction : DumbAwareAction(BetterCodeReviewBundle.message("packageColors.edit"), null, AllIcons.General.Settings) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, PackageColorsConfigurable::class.java)
    }
}
