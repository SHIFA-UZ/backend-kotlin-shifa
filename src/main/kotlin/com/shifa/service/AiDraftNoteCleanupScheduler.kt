package com.shifa.service

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AiDraftNoteCleanupScheduler(
    private val aiDraftNoteService: AiDraftNoteService
) {
    @Scheduled(cron = "0 0 2 * * *") // 2 AM daily
    fun cleanupOldDrafts() {
        aiDraftNoteService.cleanupOldDrafts()
    }
}
