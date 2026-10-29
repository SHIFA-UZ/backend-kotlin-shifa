package com.shifa.web

import com.shifa.service.StorageMigrationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Admin-only (the admin route rule in SecurityConfig). POST starts the volume-to-bucket copy, GET reports progress. */
@RestController
@RequestMapping("/api/admin/storage")
class StorageMigrationController(private val migration: StorageMigrationService) {

    @PostMapping("/migrate")
    fun start(@RequestParam(defaultValue = "false") dryRun: Boolean): StorageMigrationService.Status = migration.start(dryRun)

    @GetMapping("/migrate")
    fun status(): StorageMigrationService.Status = migration.status
}
