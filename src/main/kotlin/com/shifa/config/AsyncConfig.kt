package com.shifa.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * Enables @Async for background processing (e.g. AI scribe pipeline).
 */
@Configuration
@EnableAsync
class AsyncConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * SSE streams (Shifa AI for doctors and patients) run here instead of on unbounded raw threads (roadmap P7):
     * at most 16 concurrent streams plus a short queue; beyond that the request is rejected instead of
     * exhausting memory. The request id travels with the stream so its log lines stay correlated.
     */
    @Bean(name = ["sseTaskExecutor"])
    fun sseTaskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 16
            queueCapacity = 16
            setThreadNamePrefix("sse-")
            setTaskDecorator { task ->
                val mdc = org.slf4j.MDC.getCopyOfContextMap()
                Runnable {
                    if (mdc != null) org.slf4j.MDC.setContextMap(mdc) else org.slf4j.MDC.clear()
                    try {
                        task.run()
                    } finally {
                        org.slf4j.MDC.clear()
                    }
                }
            }
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
        }
        executor.initialize()
        log.info("SSE executor initialized: corePoolSize=4, maxPoolSize=16, queueCapacity=16")
        return executor
    }

    @Bean(name = ["scribeTaskExecutor"])
    fun scribeTaskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 4
            queueCapacity = 50
            setThreadNamePrefix("scribe-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(60)
        }
        executor.initialize()
        log.info("Scribe task executor initialized: corePoolSize=2, maxPoolSize=4, queueCapacity=50")
        return executor
    }

    @Bean(name = ["briefingTaskExecutor"])
    fun briefingTaskExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor().apply {
            // Keep concurrency low: PDFBox + OpenAI are CPU/memory heavy.
            corePoolSize = 1
            maxPoolSize = 2
            queueCapacity = 20
            setThreadNamePrefix("briefing-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(60)
            // Prefer dropping oldest queued work over unbounded CPU growth under burst bookings.
            setRejectedExecutionHandler { runnable, pool ->
                log.warn(
                    "Briefing queue saturated (active={}, queue={}); discarding oldest job",
                    pool.activeCount,
                    pool.queue.size,
                )
                java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
                    .rejectedExecution(runnable, pool)
            }
        }
        executor.initialize()
        log.info("Briefing task executor initialized: corePoolSize=1, maxPoolSize=2, queueCapacity=20")
        return executor
    }

    /** Intelligence memory extraction: best-effort, bounded; saturation drops the job with a log line. */
    @Bean(name = ["intelligenceMemoryExecutor"])
    fun intelligenceMemoryExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 2
            queueCapacity = 50
            setThreadNamePrefix("intel-mem-")
            setWaitForTasksToCompleteOnShutdown(false)
            setRejectedExecutionHandler { _, pool ->
                log.warn("Intelligence memory queue saturated (active={}, queue={}); dropping extraction", pool.activeCount, pool.queue.size)
            }
        }
        executor.initialize()
        log.info("Intelligence memory executor initialized: corePoolSize=1, maxPoolSize=2, queueCapacity=50")
        return executor
    }
}
