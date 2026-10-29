package com.shifa.service

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AiDraftNoteCleanupScheduler(
    private val aiDraftNoteService: AiDraftNoteService
) {
    @Scheduled(cron = "0 0 2 * * *") // 2 AM daily
    @SchedulerLock(name = "ai-draft-note-cleanup", lockAtLeastFor = "PT20S")
    fun cleanupOldDrafts() {
        aiDraftNoteService.cleanupOldDrafts()
    }
}
