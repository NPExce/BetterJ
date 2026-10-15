package pl.adrros.betterpullrequests.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

/**
 * Shortcuts registered on the focused component beat everything the keymap offers, so the paste and emoji
 * actions are attached straight to every review comment editor. The comment input buttons ride along.
 */
class ReviewCommentEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (!ReviewCommentEditors.isReviewCommentEditor(editor) || editor.getUserData(DISPOSABLE) != null) return
        val disposable = Disposer.newDisposable("BetterPullRequests review comment editor")
        editor.putUserData(DISPOSABLE, disposable)
        registerShortcut(editor, PASTE_IMAGE_ACTION, KeymapUtil.getActiveKeymapShortcuts(PASTE_ACTION), disposable)
        registerShortcut(editor, INSERT_EMOJI_ACTION, null, disposable)
        CommentInputButtons.watch(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        event.editor.getUserData(DISPOSABLE)?.let {
            event.editor.putUserData(DISPOSABLE, null)
            Disposer.dispose(it)
        }
    }

    /** A registered action must not get its shortcut set changed; the platform asks for a wrapper instead. */
    private fun registerShortcut(editor: Editor, actionId: String, shortcuts: ShortcutSet?, disposable: Disposable) {
        val registered = ActionManager.getInstance().getAction(actionId) ?: return
        ActionUtil.wrap(actionId).registerCustomShortcutSet(shortcuts ?: registered.shortcutSet, editor.contentComponent, disposable)
    }

    companion object {
        private val DISPOSABLE = Key.create<Disposable>("BetterPullRequests.reviewCommentEditor")
        const val PASTE_ACTION = "\$Paste"
        const val PASTE_IMAGE_ACTION = "BetterPullRequests.PasteImage"
        const val INSERT_EMOJI_ACTION = "BetterPullRequests.InsertEmoji"
    }
}
