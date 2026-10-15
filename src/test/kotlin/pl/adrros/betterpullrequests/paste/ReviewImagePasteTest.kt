package pl.adrros.betterpullrequests.paste

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import pl.adrros.betterpullrequests.ReviewEditorFixtures.connectTestTarget
import pl.adrros.betterpullrequests.ReviewEditorFixtures.openReviewCommentEditor
import pl.adrros.betterpullrequests.review.ReviewTarget
import pl.adrros.betterpullrequests.upload.AttachmentUploadException
import pl.adrros.betterpullrequests.upload.AttachmentUploadService
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage

/** Drives the real `EditorPaste` action, the road every paste shortcut, menu and context menu takes. */
class ReviewImagePasteTest : BasePlatformTestCase() {
    private class RecordingUploadService : AttachmentUploadService {
        class Upload(val target: ReviewTarget, val fileName: String, val contentType: String, val load: () -> ByteArray)

        val uploads = mutableListOf<Upload>()
        val pending = mutableListOf<(Result<String>) -> Unit>()

        override fun uploadAsync(target: ReviewTarget, fileName: String, contentType: String, load: () -> ByteArray, onFinished: (Result<String>) -> Unit) {
            uploads += Upload(target, fileName, contentType, load)
            pending += onFinished
        }
    }

    private class ClipboardContent(private val image: BufferedImage?, private val text: String?) : Transferable {
        private val flavors = listOfNotNull(image?.let { DataFlavor.imageFlavor }, text?.let { DataFlavor.stringFlavor })
        override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors
        override fun getTransferData(flavor: DataFlavor): Any = when {
            flavor == DataFlavor.imageFlavor && image != null -> image
            flavor == DataFlavor.stringFlavor && text != null -> text
            else -> throw UnsupportedFlavorException(flavor)
        }
    }

    private lateinit var uploads: RecordingUploadService
    private val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)

    override fun setUp() {
        super.setUp()
        uploads = RecordingUploadService()
        project.replaceService(AttachmentUploadService::class.java, uploads, testRootDisposable)
        connectTestTarget(testRootDisposable)
    }

    private fun paste() = myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)

    fun `test pasting a screenshot inserts a placeholder and then the image link`() {
        myFixture.openReviewCommentEditor("LGTM <caret>")
        CopyPasteManager.getInstance().setContents(ClipboardContent(image, null))

        paste()

        val upload = uploads.uploads.single()
        assertEquals("image/png", upload.contentType)
        assertTrue(upload.fileName, upload.fileName.matches(Regex("screenshot-\\d{8}-\\d{6}\\.png")))
        assertTrue(upload.load().size > 8)
        assertEquals("octo", upload.target.account.name)
        assertEquals("LGTM ![Uploading ${upload.fileName}…]()", myFixture.editor.document.text)

        uploads.pending.single().invoke(Result.success("https://github.com/user-attachments/assets/abc"))
        assertEquals("LGTM ![${upload.fileName}](https://github.com/user-attachments/assets/abc)", myFixture.editor.document.text)
    }

    fun `test an image with its address as text is still an image`() {
        myFixture.openReviewCommentEditor("<caret>")
        CopyPasteManager.getInstance().setContents(ClipboardContent(image, "https://example.com/shot.png"))

        paste()

        assertEquals(1, uploads.uploads.size)
    }

    fun `test real text next to an image pastes the text`() {
        myFixture.openReviewCommentEditor("<caret>")
        CopyPasteManager.getInstance().setContents(ClipboardContent(image, "some words here"))

        paste()

        assertEquals("some words here", myFixture.editor.document.text)
        assertTrue(uploads.uploads.isEmpty())
    }

    fun `test failed upload removes the placeholder`() {
        myFixture.openReviewCommentEditor("<caret>")
        CopyPasteManager.getInstance().setContents(ClipboardContent(image, null))

        paste()
        uploads.pending.single().invoke(Result.failure(AttachmentUploadException("boom")))

        assertEquals("", myFixture.editor.document.text)
    }

    fun `test a placeholder edited away does not get the link`() {
        myFixture.openReviewCommentEditor("<caret>")
        CopyPasteManager.getInstance().setContents(ClipboardContent(image, null))

        paste()
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText("rewritten")
        }
        uploads.pending.single().invoke(Result.success("https://github.com/user-attachments/assets/abc"))

        assertEquals("rewritten", myFixture.editor.document.text)
    }

    fun `test text on the clipboard pastes as usual`() {
        myFixture.openReviewCommentEditor("<caret>")
        CopyPasteManager.getInstance().setContents(StringSelection("plain"))

        paste()

        assertEquals("plain", myFixture.editor.document.text)
        assertTrue(uploads.uploads.isEmpty())
    }

    fun `test images are not intercepted outside review comment editors`() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "<caret>")
        CopyPasteManager.getInstance().setContents(ClipboardContent(image, null))

        paste()

        assertTrue(uploads.uploads.isEmpty())
    }

    fun `test without a connected pull request the regular paste runs`() {
        connectTestTarget(testRootDisposable, target = null)
        myFixture.openReviewCommentEditor("<caret>")
        CopyPasteManager.getInstance().setContents(ClipboardContent(image, null))

        paste()

        assertTrue(uploads.uploads.isEmpty())
    }
}
