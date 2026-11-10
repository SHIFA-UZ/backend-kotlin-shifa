package com.shifa.shared.api

import com.shifa.shared.storage.FileStorage
import com.shifa.shared.storage.StorageKeys
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

/**
 * Serves uploaded files from [FileStorage] at the paths the apps have always used, so the same URL works whether the
 * bytes are on the volume or in the bucket. Replaces the static resource handlers of the volume era and the former
 * patient-document controller (same path, same operation id). Signatures on the private prefixes are checked before
 * this by [com.shifa.shared.security.SignedUrlFilter].
 */
@RestController
class StoredFileController(private val storage: FileStorage) {

    companion object {
        private const val FLAT_DIRS = "doctors|patients|certificates|chat-attachments"
        private val ACTIVE_SUBTYPES = setOf("html", "xhtml+xml", "svg+xml", "xml", "javascript", "ecmascript", "css")
    }

    /** Patient documents: PDFs open in the viewer, everything else downloads (as before). */
    @GetMapping("/patientdocuments/{patientId}/{filename:.+}")
    fun serveDocument(@PathVariable patientId: Long, @PathVariable filename: String): ResponseEntity<Resource> =
        serve("patientdocuments/$patientId/$filename", withDisposition = true)

    /** Photos, certificates and chat attachments live in one flat directory each. */
    @GetMapping("/{dir:$FLAT_DIRS}/{filename:.+}")
    fun serveFlat(@PathVariable dir: String, @PathVariable filename: String): ResponseEntity<Resource> =
        serve("$dir/$filename", withDisposition = false)

    private fun serve(key: String, withDisposition: Boolean): ResponseEntity<Resource> {
        if (!StorageKeys.valid(key)) return ResponseEntity.notFound().build()
        val resource = storage.open(key) ?: return ResponseEntity.notFound().build()
        val filename = key.substringAfterLast('/')
        val declared = MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM)
        // An uploaded page or script would run on the API's own origin (where the admin page keeps its token):
        // those types are only ever downloaded, never rendered.
        val active = declared.subtype.lowercase() in ACTIVE_SUBTYPES
        val mediaType = if (active) MediaType.APPLICATION_OCTET_STREAM else declared
        // Doctor photos are public profile data; everything else is a patient's and stays out of shared caches.
        val cache = CacheControl.maxAge(1, TimeUnit.HOURS).let { if (key.startsWith("doctors/")) it.cachePublic() else it.cachePrivate() }
        val response = ResponseEntity.ok()
            .contentType(mediaType)
            .cacheControl(cache)
        if (withDisposition || active) {
            val disposition = if (mediaType == MediaType.APPLICATION_PDF) ContentDisposition.inline() else ContentDisposition.attachment()
            response.header("Content-Disposition", disposition.filename(filename).build().toString())
        }
        return response.body(resource)
    }

    /** CORS headers come from the Spring CORS configuration; this only keeps preflights off the 404 path. */
    @RequestMapping(
        method = [RequestMethod.OPTIONS],
        value = ["/patientdocuments/{patientId}/{filename:.+}", "/{dir:$FLAT_DIRS}/{filename:.+}"],
    )
    fun handleOptions(): ResponseEntity<Void> = ResponseEntity.ok().build()
}
