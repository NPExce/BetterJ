package pl.adrros.bettercodereview.paste

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import pl.adrros.bettercodereview.BetterCodeReviewBundle
import pl.adrros.bettercodereview.review.ReviewTarget
import pl.adrros.bettercodereview.upload.AttachmentUploadService

private val LOG = logger<ReviewImageInserter>()
private const val NOTIFICATION_GROUP = "BetterCodeReview"

/**
 * Mirrors the GitHub web flow: an `![Uploading x…]()` placeholder appears at the caret right away
 * and turns into the real image link once the upload finishes.
 */
object ReviewImageInserter {
    fun insertAndUpload(project: Project, editor: Editor, target: ReviewTarget, images: List<PastedImage>) {
        images.forEachIndexed { index, image -> insertAndUpload(project, editor, target, image, separatorBefore = index > 0) }
    }

    fun insertAndUpload(project: Project, editor: Editor, target: ReviewTarget, image: PastedImage, separatorBefore: Boolean = false) {
        if (editor.isDisposed) return
        val document = editor.document
        val placeholder = placeholderFor(image.fileName)
        val marker = WriteCommandAction.writeCommandAction(project).compute<RangeMarker, RuntimeException> {
            val selection = editor.selectionModel
            if (selection.hasSelection()) {
                document.deleteString(selection.selectionStart, selection.selectionEnd)
                selection.removeSelection()
            }
            if (separatorBefore) {
                val at = editor.caretModel.offset
                document.insertString(at, "\n")
                editor.caretModel.moveToOffset(at + 1)
            }
            val start = editor.caretModel.offset
            document.insertString(start, placeholder)
            editor.caretModel.moveToOffset(start + placeholder.length)
            document.createRangeMarker(start, start + placeholder.length)
        }
        project.service<AttachmentUploadService>().uploadAsync(target, image.fileName, image.contentType, image.load) { result ->
            result.onSuccess { url -> replace(project, document, marker, imageLink(image.fileName, url)) }
            result.onFailure { error ->
                replace(project, document, marker, "")
                notify(project, BetterCodeReviewBundle.message("upload.failed.title"), error.message ?: error.toString(), NotificationType.ERROR)
            }
        }
    }

    fun placeholderFor(fileName: String): String = "$PLACEHOLDER_PREFIX${escapeMarkdown(BetterCodeReviewBundle.message("upload.placeholder", fileName))}]()"

    private const val PLACEHOLDER_PREFIX = "!["

    fun imageLink(fileName: String, url: String): String = "![${escapeMarkdown(fileName)}]($url)"

    /** Brackets and parentheses in a file name would end the Markdown link early. */
    fun escapeMarkdown(text: String): String = text.replace(Regex("[\\[\\]()]"), "_")

    private fun replace(project: Project, document: Document, marker: RangeMarker, text: String) {
        try {
            if (!marker.isValid || !document.getText(marker.textRange).startsWith(PLACEHOLDER_PREFIX)) {
                if (text.isNotEmpty()) {
                    LOG.info("Placeholder for an uploaded image was edited away; link not inserted: $text")
                    notify(project, BetterCodeReviewBundle.message("upload.placeholder.gone.title"), text, NotificationType.INFORMATION)
                }
                return
            }
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(marker.startOffset, marker.endOffset, text)
            }
        } finally {
            marker.dispose()
        }
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}
