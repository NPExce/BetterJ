package pl.adrros.betterpullrequests.paste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class PasteShortcutInterceptorTest {
    private val macPaste = listOf(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK))
    private val pcPaste = listOf(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))

    @Test
    fun `matches the keymap paste stroke by key code and modifiers`() {
        val pressed = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK, false)
        assertTrue(PasteShortcutInterceptor.isPasteShortcut(pressed, macPaste))
        assertFalse(PasteShortcutInterceptor.isPasteShortcut(pressed, pcPaste))
    }

    @Test
    fun `plain letters and other combinations pass through`() {
        assertFalse(PasteShortcutInterceptor.isPasteShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, 0, false), macPaste))
        assertFalse(PasteShortcutInterceptor.isPasteShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK, false), macPaste))
        assertFalse(PasteShortcutInterceptor.isPasteShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, false), macPaste))
    }
}
