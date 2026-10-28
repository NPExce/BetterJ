package pl.adrros.bettercodereview.colors

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import pl.adrros.bettercodereview.BetterCodeReviewBundle
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings | Tools | Package Colors: the palette's names and colours, and whether the package line is tinted. */
class PackageColorsConfigurable(private val project: Project) : Configurable {
    private class Row(val id: Int, val name: JBTextField, val color: ColorPanel)

    private val rows = ArrayList<Row>()
    private var packageLine: JBCheckBox? = null

    override fun getDisplayName(): String = BetterCodeReviewBundle.message("packageColors.settings")

    override fun createComponent(): JComponent {
        val colors = project.service<PackageColors>()
        val form = FormBuilder.createFormBuilder()
        rows.clear()
        for (color in colors.colors) {
            val name = JBTextField(color.name, 16)
            val panel = ColorPanel().apply {
                setSupportTransparency(true)
                selectedColor = color.color
            }
            rows.add(Row(color.id, name, panel))
            form.addComponent(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(panel)
                add(name)
            })
        }
        val checkBox = JBCheckBox(BetterCodeReviewBundle.message("packageColors.packageLine"), colors.tintsPackageLine)
        packageLine = checkBox
        form.addVerticalGap(JBUI.scale(8))
        form.addComponent(checkBox)
        return JPanel(java.awt.BorderLayout()).apply { add(form.panel, java.awt.BorderLayout.NORTH) }
    }

    private fun edited(): List<PackageColors.PaletteColor> =
        rows.mapNotNull { row -> row.color.selectedColor?.let { PackageColors.PaletteColor(row.id, row.name.text.trim().ifEmpty { "Color ${row.id}" }, it) } }

    override fun isModified(): Boolean {
        val colors = project.service<PackageColors>()
        return edited() != colors.colors || packageLine?.isSelected != colors.tintsPackageLine
    }

    override fun apply() {
        project.service<PackageColors>().update(edited(), packageLine?.isSelected ?: true)
    }

    override fun reset() {
        val colors = project.service<PackageColors>().colors.associateBy { it.id }
        for (row in rows) {
            val color = colors[row.id] ?: continue
            row.name.text = color.name
            row.color.selectedColor = color.color
        }
        packageLine?.isSelected = project.service<PackageColors>().tintsPackageLine
    }

    override fun disposeUIResources() {
        rows.clear()
        packageLine = null
    }
}
