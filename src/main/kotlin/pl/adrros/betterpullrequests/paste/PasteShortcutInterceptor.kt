package pl.adrros.betterpullrequests.paste

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapUtil
import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Sees every key event before the action system does. When the paste shortcut of the active keymap lands in a
 * review comment editor while the clipboard holds an image and no text, the image is uploaded right here;
 * otherwise the event passes on untouched. This sidesteps the ordering games between the platform paste,
 * other plugins' paste handlers and our own action, which left Cmd+V doing nothing but beeping.
 */
private val LOG = logger<PasteShortcutInterceptor>()

@Service(Service.Level.APP)
class PasteShortcutInterceptor : Disposable {
    private var installed = false

    @Synchronized
    fun ensureInstalled() {
        if (installed) return
        IdeEventQueue.getInstance().addDispatcher({ event -> intercept(event) }, this)
        installed = true
        LOG.info("Paste shortcut interceptor installed for ${activePasteStrokes()}")
    }

    private fun intercept(event: AWTEvent): Boolean {
        if (event !is KeyEvent || event.id != KeyEvent.KEY_PRESSED || event.isConsumed) return false
        if (!isPasteShortcut(KeyStroke.getKeyStrokeForEvent(event))) return false
        val editor = focusedEditor()
        if (editor == null) {
            LOG.info("Paste shortcut: no editor under focus owner ${KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.javaClass?.name}")
            return false
        }
        if (!PasteReviewImageAction.applies(editor)) {
            LOG.info("Paste shortcut skipped: reviewEditor=${pl.adrros.betterpullrequests.review.ReviewCommentEditors.isReviewCommentEditor(editor)} " +
                "imagesOnly=${PastedImages.clipboardHasImagesOnly()} file=${com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)}")
            return false
        }
        val project = editor.project ?: return false
        LOG.info("Paste shortcut: uploading clipboard image into review comment editor")
        event.consume()
        PastedImages.fromClipboard().forEach { ReviewImageInserter.insertAndUpload(project, editor, it) }
        return true
    }

    private fun focusedEditor(): Editor? {
        val focusOwner: Component = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return null
        return DataManager.getInstance().getDataContext(focusOwner).getData(CommonDataKeys.EDITOR)
    }

    override fun dispose() {
        installed = false
    }

    companion object {
        const val PASTE_ACTION_ID = "\$Paste"

        fun isPasteShortcut(stroke: KeyStroke, pasteStrokes: List<KeyStroke> = activePasteStrokes()): Boolean =
            pasteStrokes.any { it.keyCode == stroke.keyCode && it.modifiers == stroke.modifiers }

        fun activePasteStrokes(): List<KeyStroke> =
            KeymapUtil.getActiveKeymapShortcuts(PASTE_ACTION_ID).shortcuts
                .filterIsInstance<KeyboardShortcut>()
                .filter { it.secondKeyStroke == null }
                .map { it.firstKeyStroke }
    }
}
