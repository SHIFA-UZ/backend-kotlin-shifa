// src/main/kotlin/com/shifa/web/ChatAttachmentController.kt
package com.shifa.web

import com.shifa.config.AppProperties
import com.shifa.service.PatientDocumentStorageService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@RestController
@RequestMapping("/api/messages")
class ChatAttachmentController(
    private val storage: PatientDocumentStorageService,
    private val appProperties: AppProperties
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
        try {
            // Generate unique filename
            val originalFilename = file.originalFilename ?: "attachment"
            val extension = originalFilename.substringAfterLast('.', "")
            val uniqueFilename = "${UUID.randomUUID()}.$extension"
            
            // Save to chat attachments directory
            val storageRoot = Paths.get(appProperties.storageRoot).toAbsolutePath().normalize()
            val chatAttachmentsDir = storageRoot.resolve("chat-attachments")
            Files.createDirectories(chatAttachmentsDir)
            
            val targetPath = chatAttachmentsDir.resolve(uniqueFilename)
            file.transferTo(targetPath.toFile())
            
            // Save thumbnail if provided
            var thumbnailUrl: String? = null
            if (thumbnail != null && !thumbnail.isEmpty) {
                val thumbnailFilename = "thumb_$uniqueFilename"
                val thumbnailPath = chatAttachmentsDir.resolve(thumbnailFilename)
                thumbnail.transferTo(thumbnailPath.toFile())
                thumbnailUrl = "${appProperties.publicBaseUrl.removeSuffix("/")}/chat-attachments/$thumbnailFilename"
            }
            
            // Build public URL
            val publicUrl = "${appProperties.publicBaseUrl.removeSuffix("/")}/chat-attachments/$uniqueFilename"
            
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
