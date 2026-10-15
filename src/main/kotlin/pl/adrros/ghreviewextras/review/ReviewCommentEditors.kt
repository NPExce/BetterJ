package pl.adrros.ghreviewextras.review

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * The bundled code review UI (GitHub and GitLab plugins) edits comments in an editor backed by an
 * in-memory file named `Dummy.md` - see `CodeReviewMarkdownEditor` in the collaboration tools.
 * That file name is the only stable marker those editors carry.
 */
object ReviewCommentEditors {
    const val COMMENT_FILE_NAME = "Dummy.md"

    fun isReviewCommentEditor(editor: Editor): Boolean = isReviewCommentDocument(editor.document)

    fun isReviewCommentDocument(document: Document): Boolean {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return false
        return isReviewCommentFile(file)
    }

    fun isReviewCommentFile(file: VirtualFile): Boolean =
        file.name == COMMENT_FILE_NAME && !file.isInLocalFileSystem
}
