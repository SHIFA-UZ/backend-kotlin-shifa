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
}
