package pl.adrros.bettercodereview.settings

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import pl.adrros.bettercodereview.BetterCodeReviewBundle
import pl.adrros.bettercodereview.colors.PackageColorsRefresher
import pl.adrros.bettercodereview.commit.CommitInclusionMarks
import pl.adrros.bettercodereview.commit.ProjectTreeTicks
import pl.adrros.bettercodereview.editor.WindowCenteredCode
import java.awt.Window

/** Every feature that can be switched off, with the key its setting has always been stored under. */
enum class Feature(private val key: String, private val default: Boolean) {
    LINE_COUNTS("BetterCodeReview.lineCounts", true),
    CURRENT_FILE("BetterCodeReview.currentFileMark", true),
    COMMIT_MARKS("BetterCodeReview.commitCheckboxMarks", true),
    VIEWED_CYCLE("BetterCodeReview.viewedCheckboxCycle", true),
    DOUBLE_CLICK_OPENS("BetterCodeReview.doubleClickOpensFile", true),
    PACKAGE_COLORS("BetterCodeReview.packageColors", true),
    CENTER_ON_WINDOW("BetterCodeReview.windowCenteredCode", false),
    ;

    var isOn: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(key, default)
        set(value) = PropertiesComponent.getInstance().setValue(key, value, default)

    val title: String get() = BetterCodeReviewBundle.message("feature.$name.text")
    val description: String get() = BetterCodeReviewBundle.message("feature.$name.description")
}

object Features {
    /** Switches [feature] and redraws what shows it. */
    fun set(feature: Feature, on: Boolean) {
        if (feature.isOn == on) return
        feature.isOn = on
        when (feature) {
            Feature.CENTER_ON_WINDOW -> WindowCenteredCode.switched(on)
            Feature.PACKAGE_COLORS -> ProjectManager.getInstance().openProjects.forEach(PackageColorsRefresher()::colorsChanged)
            Feature.COMMIT_MARKS -> {
                CommitInclusionMarks.repaintAll()
                ProjectTreeTicks.repaintAll()
            }
            else -> Unit
        }
        Window.getWindows().forEach(Window::repaint)
    }
}

/** Tools | BetterJ: each feature with a tick, and the settings page. */
class FeaturesMenu : DefaultActionGroup(), DumbAware {
    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        (Feature.entries.map(::FeatureToggle) + Separator.getInstance() + OpenSettings()).toTypedArray()

    private class FeatureToggle(private val feature: Feature) : ToggleAction(feature.title, feature.description, null), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = feature.isOn
        override fun setSelected(e: AnActionEvent, state: Boolean) = Features.set(feature, state)
    }

    private class OpenSettings : DumbAwareAction(BetterCodeReviewBundle.message("features.openSettings")) {
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, FeaturesConfigurable::class.java)
        }
    }
}

/** Settings | Tools | BetterJ: a checkbox per feature, with what it does underneath. */
class FeaturesConfigurable : BoundConfigurable(BetterCodeReviewBundle.message("features.settings")) {
    override fun createPanel(): DialogPanel = panel {
        for (feature in Feature.entries) {
            row {
                checkBox(feature.title)
                    .comment(feature.description)
                    .bindSelected({ feature.isOn }, { Features.set(feature, it) })
            }
        }
    }
}
