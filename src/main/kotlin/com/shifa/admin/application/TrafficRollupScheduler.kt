package com.shifa.admin.application

import com.shifa.shared.api.HttpTrafficWindow
import jakarta.annotation.PreDestroy
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Moves the request filter's counts into the database every minute, and once more on shutdown so a deploy loses nothing. */
@Component
class TrafficRollupScheduler(
    private val traffic: HttpTrafficWindow,
    private val rollups: TrafficRollupService,
) {
    private val log = LoggerFactory.getLogger(TrafficRollupScheduler::class.java)

    @Scheduled(fixedDelayString = "PT1M")
    @SchedulerLock(name = "traffic-rollup", lockAtLeastFor = "PT20S")
    fun flush() = save()

    @Scheduled(cron = "0 15 3 * * *")
    @SchedulerLock(name = "traffic-rollup-cleanup", lockAtLeastFor = "PT20S")
    fun cleanup() = rollups.deleteExpired()

    @PreDestroy
    fun flushOnShutdown() = save()

    /** A failed save keeps the counts in memory for the next run: losing traffic numbers is never worth failing over. */
    private fun save() {
        val deltas = traffic.drain()
        if (deltas.isEmpty()) return
        runCatching { rollups.save(deltas) }.onFailure {
            traffic.restore(deltas)
            log.warn("Traffic rollup not saved, keeping {} hours for the next run: {}", deltas.size, it.message)
        }
    }
}
