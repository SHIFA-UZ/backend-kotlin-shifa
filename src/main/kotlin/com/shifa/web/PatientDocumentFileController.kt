// src/main/kotlin/com/shifa/web/PatientDocumentFileController.kt
package com.shifa.web

import com.shifa.config.AppProperties
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@RestController
@RequestMapping("/patientdocuments")
class PatientDocumentFileController(
    private val appProperties: AppProperties
) {

    /**
     * Serve patient document files with proper Content-Type headers and CORS support.
     * GET /patientdocuments/{patientId}/{filename}
     */
    @GetMapping("/{patientId}/{filename:.+}")
    fun serveDocument(
        @PathVariable patientId: Long,
        @PathVariable filename: String
    ): ResponseEntity<Resource> {
        try {
            val storageRoot = Paths.get(appProperties.storageRoot).toAbsolutePath().normalize()
            val filePath = storageRoot.resolve("patientdocuments")
                .resolve(patientId.toString())
                .resolve(filename)
                .normalize()

            // Security check: ensure the file is within the storage root
            if (!filePath.startsWith(storageRoot)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
            }

            // Check if file exists
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
            }

            val resource = FileSystemResource(filePath.toFile())
            
            // Determine content type
            val contentType = Files.probeContentType(filePath)
                ?: when {
                    filename.lowercase().endsWith(".pdf") -> "application/pdf"
                    filename.lowercase().endsWith(".jpg") || filename.lowercase().endsWith(".jpeg") -> "image/jpeg"
                    filename.lowercase().endsWith(".png") -> "image/png"
                    else -> "application/octet-stream"
                }

            val mediaType: MediaType = MediaType.parseMediaType(contentType)
            val headers = HttpHeaders().apply {
                setContentType(mediaType)
                // Do NOT add Access-Control-Allow-Origin here - Spring CORS config handles it.
                // Adding it here causes "multiple values" (e.g. "origin, *") and browser rejects.
                // Add content disposition for inline viewing (especially for PDFs)
                if (contentType == "application/pdf") {
                    setContentDisposition(ContentDisposition.inline().filename(filename).build())
                } else {
                    setContentDisposition(ContentDisposition.attachment().filename(filename).build())
                }
                // Cache control
                cacheControl = "public, max-age=3600"
            }

            return ResponseEntity.ok()
                .headers(headers)
                .body(resource)
        } catch (e: Exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    /**
     * Handle OPTIONS request for CORS preflight (CORS headers come from Spring CORS config).
     */
    @RequestMapping(value = ["/{patientId}/{filename:.+}"], method = [RequestMethod.OPTIONS])
    fun handleOptions(): ResponseEntity<Void> {
        return ResponseEntity.ok().build()
    }
}
