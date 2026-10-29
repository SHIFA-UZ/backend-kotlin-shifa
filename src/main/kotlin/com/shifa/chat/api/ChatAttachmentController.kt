// src/main/kotlin/com/shifa/chat/api/ChatAttachmentController.kt
package com.shifa.chat.api

import com.shifa.shared.config.AppProperties
import com.shifa.shared.storage.FileStorage
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/messages")
class ChatAttachmentController(
    private val storage: FileStorage,
    private val appProperties: AppProperties,
    private val signer: com.shifa.shared.security.FileUrlSigner,
    private val uploadGuard: com.shifa.shared.UploadGuard,
) {

    /**
     * POST /api/messages/upload-attachment
     * Upload chat attachment (image, voice, document) and return public URL
     */
    @PostMapping("/upload-attachment")
    fun uploadAttachment(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(value = "thumbnail", required = false) thumbnail: MultipartFile?
    ): ResponseEntity<Map<String, String>> {
        uploadGuard.check(com.shifa.shared.UploadPolicy.Kind.CHAT_ATTACHMENT, file)
        if (thumbnail != null && !thumbnail.isEmpty) uploadGuard.check(com.shifa.shared.UploadPolicy.Kind.IMAGE, thumbnail)
        try {
            // Generate unique filename
            val originalFilename = file.originalFilename ?: "attachment"
            val extension = originalFilename.substringAfterLast('.', "")
            val uniqueFilename = "${UUID.randomUUID()}.$extension"
            
            file.inputStream.use { storage.put("chat-attachments/$uniqueFilename", it, file.size, file.contentType) }

            // Save thumbnail if provided
            var thumbnailUrl: String? = null
            if (thumbnail != null && !thumbnail.isEmpty) {
                val thumbnailFilename = "thumb_$uniqueFilename"
                thumbnail.inputStream.use { storage.put("chat-attachments/$thumbnailFilename", it, thumbnail.size, thumbnail.contentType) }
                thumbnailUrl = signer.sign("${appProperties.publicBaseUrl.removeSuffix("/")}/chat-attachments/$thumbnailFilename")
            }
            
            // Build public URL
            val publicUrl = signer.sign("${appProperties.publicBaseUrl.removeSuffix("/")}/chat-attachments/$uniqueFilename")
            
            return ResponseEntity.ok(mapOf<String, String>(
                "url" to publicUrl,
                "thumbnailUrl" to (thumbnailUrl ?: ""),
                "fileName" to originalFilename,
                "fileSize" to file.size.toString()
            ))
        } catch (e: Exception) {
            return ResponseEntity.status(500).body(mapOf<String, String>("error" to (e.message ?: "Upload failed")))
        }
    }
}
