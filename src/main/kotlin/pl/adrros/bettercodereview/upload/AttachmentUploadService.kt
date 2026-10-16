package pl.adrros.bettercodereview.upload

import pl.adrros.bettercodereview.review.ReviewTarget

class AttachmentUploadException(message: String) : RuntimeException(message)

/** Uploads an attachment for the given pull request target and hands back its URL on the UI thread. */
interface AttachmentUploadService {
    fun uploadAsync(target: ReviewTarget, fileName: String, contentType: String, load: () -> ByteArray, onFinished: (Result<String>) -> Unit)
}
