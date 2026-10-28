package com.shifa.shared

import com.shifa.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.Locale

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif")
private val DOCUMENT_EXTENSIONS = IMAGE_EXTENSIONS + setOf("pdf", "doc", "docx")
private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "wav", "ogg", "webm", "mp4", "flac")

/**
 * Which file types each upload path accepts. The lists come from what production stores today
 * (pdf, png, jpg, jpeg, m4a) plus what the apps' pickers can offer (doc/docx, HEIC from iOS, scribe audio).
 * Pure decisions; [UploadGuard] applies them.
 */
object UploadPolicy {
    enum class Kind(val extensions: Set<String>) {
        /** Patient photo, doctor avatar, chat thumbnail. */
        IMAGE(IMAGE_EXTENSIONS),
        /** Patient documents, doctor certificates. */
        DOCUMENT(DOCUMENT_EXTENSIONS),
        /** Chat attachments: image, document or voice message. */
        CHAT_ATTACHMENT(DOCUMENT_EXTENSIONS + AUDIO_EXTENSIONS),
        /** Scribe recordings and voice notes sent to transcription; never served back. */
        AUDIO(AUDIO_EXTENSIONS),
    }

    data class Verdict(val extension: String, val reason: String?) {
        val allowed: Boolean get() = reason == null
    }

    private val EXTENSION = Regex("[a-z0-9]{1,8}")

    /** Lower-case extension of the client-supplied file name, or "" when there is none or it is not a plain token. */
    fun extension(originalFilename: String?): String {
        val name = originalFilename.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (EXTENSION.matches(ext)) ext else ""
    }

    fun check(kind: Kind, originalFilename: String?): Verdict {
        val ext = extension(originalFilename)
        return when {
            ext.isEmpty() -> Verdict(ext, "no usable extension")
            ext !in kind.extensions -> Verdict(ext, "extension not allowed for $kind")
            else -> Verdict(ext, null)
        }
    }
}

/** Applies [UploadPolicy]; refuses with 400 only once `app.upload-policy-enforce` is true, otherwise logs. */
@Component
class UploadGuard(private val app: AppProperties) {
    private val log = LoggerFactory.getLogger(UploadGuard::class.java)

    fun check(kind: UploadPolicy.Kind, file: MultipartFile) {
        val verdict = UploadPolicy.check(kind, file.originalFilename)
        if (verdict.allowed) return
        if (app.uploadPolicyEnforce) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File type not allowed")
        }
        // Media type only: parameters such as name="..." could carry the client's file name.
        val mediaType = file.contentType?.substringBefore(';')?.trim()
        log.warn(
            "Upload would be refused (enforcement off): kind={} ext={} contentType={} size={} reason={}",
            kind, verdict.extension, mediaType, file.size, verdict.reason,
        )
    }
}
