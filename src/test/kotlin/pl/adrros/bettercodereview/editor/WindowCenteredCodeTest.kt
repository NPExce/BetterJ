package pl.adrros.bettercodereview.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pl.adrros.bettercodereview.settings.Feature
import pl.adrros.bettercodereview.settings.Features

class WindowCenteredCodeTest : BasePlatformTestCase() {
    fun `test the code column sits in the middle of the window, not of the editor`() {
        assertEquals("the window's middle", 550, WindowCenteredCode.targetTextLeft(2000, 300, 1700, 900, 0))
        assertEquals("a left tool window that leaves room: same place", 550, WindowCenteredCode.targetTextLeft(2000, 850, 1150, 900, 300))
        assertEquals("a narrow editor: as far right as the column still fits", 400, WindowCenteredCode.targetTextLeft(2000, 300, 1000, 900, 0))
        assertEquals("never left of the text without a pad", 900, WindowCenteredCode.targetTextLeft(1000, 900, 1100, 900, 0))
        assertEquals("the 0.20.5 log: window 3337, column 1040", 1148, WindowCenteredCode.targetTextLeft(3337, 1151, 2180, 1040, 494))
    }

    fun `test the text glides to a new place instead of jumping, and stays put otherwise`() {
        val glide = WindowCenteredCode.Glide()
        try {
            assertEquals("the first place is taken at once", 500, glide.positionAt(500, 0) {})
            assertEquals("a pixel or two is not worth moving", 500, glide.positionAt(502, 10) {})
            assertEquals("a new place: the glide starts where the text is", 500, glide.positionAt(700, 1000) {})
            val halfway = glide.positionAt(700, 1090) {}
            assertTrue("eased out: past the middle at half the time, $halfway", halfway in 601..699)
            assertEquals("arrived", 700, glide.positionAt(700, 1180) {})
            assertEquals("and stays", 700, glide.positionAt(700, 5000) {})
            glide.positionAt(300, 6000) {}
            assertEquals("pushed by a tool window: at once", 900, glide.jumpTo(900))
            assertEquals("no glide left to finish", 900, glide.positionAt(900, 6010) {})
            assertFalse(glide.isRunning)
        } finally {
            glide.stop()
        }
    }

    fun `test turning it on uses the IDE's Center View with no margin, and off puts both back`() {
        val ideCenter = com.intellij.openapi.util.registry.Registry.get("editor.distraction.free.mode")
        val margin = "editor.distraction.free.margin"
        val before = Triple(Feature.CENTER_ON_WINDOW.isOn, ideCenter.asBoolean(), com.intellij.openapi.options.advanced.AdvancedSettings.getInt(margin))
        try {
            Features.set(Feature.CENTER_ON_WINDOW, false)
            ideCenter.setValue(false)
            com.intellij.openapi.options.advanced.AdvancedSettings.setInt(margin, 7)
            val action = ActionManager.getInstance().getAction("BetterCodeReview.WindowCenteredCode") as ToggleAction
            action.setSelected(TestActionEvent.createTestEvent(action), true)
            assertTrue(Feature.CENTER_ON_WINDOW.isOn)
            assertTrue("the editor sizes the strip on every layout in this mode", ideCenter.asBoolean())
            assertEquals("the IDE adds nothing of its own", 0, com.intellij.openapi.options.advanced.AdvancedSettings.getInt(margin))
            action.setSelected(TestActionEvent.createTestEvent(action), false)
            assertFalse(ideCenter.asBoolean())
            assertEquals(7, com.intellij.openapi.options.advanced.AdvancedSettings.getInt(margin))
        } finally {
            Features.set(Feature.CENTER_ON_WINDOW, before.first)
            ideCenter.setValue(before.second)
            com.intellij.openapi.options.advanced.AdvancedSettings.setInt(margin, before.third)
        }
    }
}
