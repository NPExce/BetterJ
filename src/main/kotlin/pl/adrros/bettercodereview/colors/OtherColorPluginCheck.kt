package pl.adrros.bettercodereview.colors

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import pl.adrros.bettercodereview.BetterCodeReviewBundle

/** With ProjectTree Color Highlighter enabled its colours win in the trees and tabs, so the developer is told once per project. */
class OtherColorPluginCheck : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (OTHER_PLUGIN_IDS.map(PluginId::getId).none { PluginManagerCore.isPluginInstalled(it) && !PluginManagerCore.isDisabled(it) }) return
        NotificationGroupManager.getInstance().getNotificationGroup("BetterCodeReview")
            .createNotification(BetterCodeReviewBundle.message("packageColors.otherPlugin", "ProjectTree Color Highlighter"), NotificationType.INFORMATION)
            .notify(project)
    }

    companion object {
        val OTHER_PLUGIN_IDS = listOf("dev.pnbarxAndTalos0248.idea.treecolor", "dev.pnbarx.idea.treecolor")
    }
}
