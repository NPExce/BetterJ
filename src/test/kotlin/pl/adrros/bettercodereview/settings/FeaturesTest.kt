package pl.adrros.bettercodereview.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FeaturesTest : BasePlatformTestCase() {
    fun `test Tools BetterJ lists every feature with a tick and the settings page`() {
        val menu = ActionManager.getInstance().getAction("BetterCodeReview.Features") as FeaturesMenu
        val toggles = menu.getChildren(null).filterIsInstance<ToggleAction>()
        assertEquals(Feature.entries.map { it.title }, toggles.map { it.templatePresentation.text })
        assertTrue("every feature has a description", Feature.entries.all { it.description.isNotBlank() })

        val before = Feature.LINE_COUNTS.isOn
        try {
            val lineCounts = toggles[Feature.entries.indexOf(Feature.LINE_COUNTS)]
            lineCounts.setSelected(TestActionEvent.createTestEvent(lineCounts), !before)
            assertEquals(!before, Feature.LINE_COUNTS.isOn)
            assertEquals(!before, lineCounts.isSelected(TestActionEvent.createTestEvent(lineCounts)))
        } finally {
            Features.set(Feature.LINE_COUNTS, before)
        }
    }

    fun `test the settings page shows a checkbox per feature and applies it`() {
        val before = Feature.CURRENT_FILE.isOn
        val page = FeaturesConfigurable()
        try {
            val component = page.createComponent()!!
            val boxes = com.intellij.util.ui.UIUtil.findComponentsOfType(component, javax.swing.JCheckBox::class.java)
            assertEquals(Feature.entries.size, boxes.size)
            boxes[Feature.entries.indexOf(Feature.CURRENT_FILE)].isSelected = !before
            assertTrue(page.isModified)
            page.apply()
            assertEquals(!before, Feature.CURRENT_FILE.isOn)
        } finally {
            page.disposeUIResources()
            Features.set(Feature.CURRENT_FILE, before)
        }
    }
}
