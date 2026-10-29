package com.shifa.shared

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** One line per hour with the numbers the JVM sizing and pool decisions need (roadmap 1.11, 2.1). */
@Component
class RuntimeMetricsLogger(private val registry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(RuntimeMetricsLogger::class.java)

    @Scheduled(initialDelayString = "PT5M", fixedRateString = "PT1H")
    fun logSummary() {
        fun gauge(name: String, vararg tags: String): Long =
            registry.find(name).tags(*tags).gauges().sumOf { it.value() }.toLong()
        val mb = 1024 * 1024
        log.info(
            """{"evt":"runtime","heapUsedMb":${gauge("jvm.memory.used", "area", "heap") / mb},"heapMaxMb":${gauge("jvm.memory.max", "area", "heap") / mb},""" +
                """"nonHeapUsedMb":${gauge("jvm.memory.used", "area", "nonheap") / mb},"threads":${gauge("jvm.threads.live")},""" +
                """"dbActive":${gauge("hikaricp.connections.active")},"dbIdle":${gauge("hikaricp.connections.idle")},"dbPending":${gauge("hikaricp.connections.pending")}}""",
        )
    }
}
