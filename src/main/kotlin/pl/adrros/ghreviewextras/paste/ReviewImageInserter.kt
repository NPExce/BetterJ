package pl.adrros.ghreviewextras.paste

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import pl.adrros.ghreviewextras.GhReviewExtrasBundle
import pl.adrros.ghreviewextras.upload.AttachmentUploadService

private const val NOTIFICATION_GROUP = "GitHub Review Extras"

/**
 * Mirrors the GitHub web flow: an `![Uploading x…]()` placeholder appears at the caret right away
 * and turns into the real image link once the upload finishes.
 */
object ReviewImageInserter {
    fun insertAndUpload(project: Project, editor: Editor, image: PastedImage) {
        val document = editor.document
        val placeholder = placeholderFor(image.fileName)
        val marker = WriteCommandAction.writeCommandAction(project).compute<RangeMarker, RuntimeException> {
            val start = editor.caretModel.offset
            document.insertString(start, placeholder)
            editor.caretModel.moveToOffset(start + placeholder.length)
            document.createRangeMarker(start, start + placeholder.length)
        }
        project.service<AttachmentUploadService>().uploadAsync(image.fileName, image.contentType, image.bytes) { result ->
            result.onSuccess { url -> replace(project, document, marker, imageLink(image.fileName, url)) }
            result.onFailure { error ->
                replace(project, document, marker, "")
                notifyFailure(project, error.message ?: error.toString())
            }
        }
    }

    fun placeholderFor(fileName: String): String = "![${GhReviewExtrasBundle.message("upload.placeholder", fileName)}]()"

    fun imageLink(fileName: String, url: String): String = "![$fileName]($url)"

    private fun replace(project: Project, document: Document, marker: RangeMarker, text: String) {
        if (!marker.isValid) return
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(marker.startOffset, marker.endOffset, text)
        }
        marker.dispose()
    }

    private fun notifyFailure(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(GhReviewExtrasBundle.message("upload.failed.title"), message, NotificationType.ERROR)
            .notify(project)
    }
}
