package pl.adrros.betterpullrequests.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

private val LOG = logger<ReviewCommentEditorListener>()

/**
 * Every review comment editor gets the emoji shortcut registered on its component (component shortcuts beat the
 * keymap, so the picker opens even inside a diff) and the buttons under the field. Pasting is handled by
 * [pl.adrros.betterpullrequests.paste.PasteShortcutInterceptor] and needs nothing per editor.
 */
class ReviewCommentEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        ReviewUiDecorationService.guarded {
            service<ReviewUiDecorationService>().ensureInstalled()
            decorate(event.editor)
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        event.editor.getUserData(DISPOSABLE)?.let {
            event.editor.putUserData(DISPOSABLE, null)
            Disposer.dispose(it)
        }
    }

    companion object {
        private val DISPOSABLE = Key.create<Disposable>("BetterPullRequests.reviewCommentEditor")
        const val PASTE_IMAGE_ACTION = "BetterPullRequests.PasteImage"
        const val INSERT_EMOJI_ACTION = "BetterPullRequests.InsertEmoji"

        /** Also used at plugin start for editors that already exist, e.g. after an install without restart. */
        fun decorate(editor: Editor) {
            if (!ReviewCommentEditors.isReviewCommentEditor(editor) || editor.getUserData(DISPOSABLE) != null) return
            LOG.info("Decorating review comment editor for ${FileDocumentManager.getInstance().getFile(editor.document)} in ${editor.project?.name}")
            val decorations = service<ReviewUiDecorationService>()
            if (decorations.isDisposed) return
            val disposable = Disposer.newDisposable(decorations, "BetterPullRequests review comment editor")
            editor.putUserData(DISPOSABLE, disposable)
            registerEmojiShortcut(editor, disposable)
            CommentInputButtons.watch(editor)
        }

        /** A registered action must not get its shortcut set changed; the platform asks for a wrapper instead. */
        private fun registerEmojiShortcut(editor: Editor, disposable: Disposable) {
            val registered = ActionManager.getInstance().getAction(INSERT_EMOJI_ACTION) ?: return
            ActionUtil.wrap(INSERT_EMOJI_ACTION).registerCustomShortcutSet(registered.shortcutSet, editor.contentComponent, disposable)
        }
    }
}
