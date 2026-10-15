package pl.adrros.ghreviewextras.upload

class AttachmentUploadException(message: String) : RuntimeException(message)

/** Uploads an attachment for the pull request currently opened in the project and hands back its URL. */
interface AttachmentUploadService {
    fun uploadAsync(fileName: String, contentType: String, bytes: ByteArray, onFinished: (Result<String>) -> Unit)
}
