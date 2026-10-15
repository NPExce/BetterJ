package pl.adrros.betterpullrequests.paste

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.TestActionEvent
import com.intellij.openapi.editor.ex.EditorEx
import pl.adrros.betterpullrequests.ReviewEditorFixtures.openReviewCommentEditor
import com.intellij.openapi.fileTypes.PlainTextFileType
import pl.adrros.betterpullrequests.upload.AttachmentUploadException
import pl.adrros.betterpullrequests.upload.AttachmentUploadService
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage

class PasteReviewImageActionTest : BasePlatformTestCase() {
    private class RecordingUploadService : AttachmentUploadService {
        val uploads = mutableListOf<Triple<String, String, ByteArray>>()
        var pending: ((Result<String>) -> Unit)? = null

        override fun uploadAsync(fileName: String, contentType: String, bytes: ByteArray, onFinished: (Result<String>) -> Unit) {
            uploads += Triple(fileName, contentType, bytes)
            pending = onFinished
        }
    }

    private class ImageTransferable(private val image: BufferedImage) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
    }

    private lateinit var uploads: RecordingUploadService

    override fun setUp() {
        super.setUp()
        uploads = RecordingUploadService()
        project.replaceService(AttachmentUploadService::class.java, uploads, testRootDisposable)
    }

    fun `test pasted image in a review comment becomes a placeholder and then an image link`() {
        myFixture.openReviewCommentEditor("LGTM <caret>")
        CopyPasteManager.getInstance().setContents(ImageTransferable(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)))

        pasteImage()

        val (fileName, contentType, bytes) = uploads.uploads.single()
        assertEquals("image/png", contentType)
        assertTrue(fileName, fileName.matches(Regex("screenshot-\\d{8}-\\d{6}\\.png")))
        assertTrue(bytes.size > 8)
        assertEquals("LGTM ![Uploading $fileName…]()", myFixture.editor.document.text)

        uploads.pending!!.invoke(Result.success("https://github.com/user-attachments/assets/abc"))
        assertEquals("LGTM ![$fileName](https://github.com/user-attachments/assets/abc)", myFixture.editor.document.text)
    }

    fun `test failed upload removes the placeholder`() {
        myFixture.openReviewCommentEditor("<caret>")
        CopyPasteManager.getInstance().setContents(ImageTransferable(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)))

        pasteImage()
        uploads.pending!!.invoke(Result.failure(AttachmentUploadException("boom")))

        assertEquals("", myFixture.editor.document.text)
    }

    fun `test text on the clipboard leaves the action disabled so the regular paste runs`() {
        myFixture.openReviewCommentEditor("<caret>")
        CopyPasteManager.getInstance().setContents(StringSelection("plain"))

        assertFalse(pasteImageEnabled())
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)

        assertEquals("plain", myFixture.editor.document.text)
        assertTrue(uploads.uploads.isEmpty())
    }

    fun `test images are not intercepted outside review comment editors`() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "<caret>")
        CopyPasteManager.getInstance().setContents(ImageTransferable(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)))

        assertFalse(pasteImageEnabled())
        assertTrue(uploads.uploads.isEmpty())
    }

    private fun pasteImage() {
        assertTrue("paste action should be enabled", pasteImageEnabled())
        myFixture.testAction(PasteReviewImageAction())
    }

    private fun pasteImageEnabled(): Boolean {
        val action = PasteReviewImageAction()
        val event = TestActionEvent.createTestEvent(action, (myFixture.editor as EditorEx).dataContext)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }
}
