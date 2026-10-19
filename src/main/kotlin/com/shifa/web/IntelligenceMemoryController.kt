package com.shifa.web

import com.shifa.ai.memory.IntelligenceMemoryService
import com.shifa.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Lifecycle of Shifa Intelligence memories for the authenticated doctor.
 * Scope comes from the JWT principal only; ids from other doctors resolve to 404.
 */
@RestController
@RequestMapping("/api/ai/memories")
class IntelligenceMemoryController(
    private val memoryService: IntelligenceMemoryService,
) {
    @GetMapping
    fun list(@AuthenticationPrincipal principal: DoctorPrincipal): Map<String, Any?> {
        val items = memoryService.list(principal.profile).map {
            mapOf(
                "id" to it.id,
                "scope" to it.scope.name,
                "type" to it.type.name,
                "content" to it.content,
                "key" to it.contentKey,
                "confidence" to it.confidence,
                "createdAt" to it.createdAt.toString(),
                "expiresAt" to it.expiresAt?.toString(),
            )
        }
        return mapOf("enabled" to memoryService.enabled(), "items" to items)
    }

    @DeleteMapping("/{id}")
    fun delete(@AuthenticationPrincipal principal: DoctorPrincipal, @PathVariable id: Long): ResponseEntity<Void> =
        if (memoryService.delete(principal.profile, id)) ResponseEntity.noContent().build()
        else ResponseEntity.status(HttpStatus.NOT_FOUND).build()

    @DeleteMapping
    fun deleteAll(@AuthenticationPrincipal principal: DoctorPrincipal): Map<String, Any?> =
        mapOf("deleted" to memoryService.deleteAll(principal.profile))
}
