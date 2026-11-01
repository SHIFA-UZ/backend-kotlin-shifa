package com.shifa.admin.api

import com.shifa.admin.api.dto.AdminMetricsDto
import com.shifa.admin.api.dto.TrafficRangeDto
import com.shifa.admin.application.MetricsService
import com.shifa.admin.application.TrafficRange
import com.shifa.admin.application.TrafficRollupService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Operations view of the running service for the admin dashboard page (`/admin-metrics/`); the admin routes are ADMIN only. */
@RestController
@RequestMapping("/api/admin/metrics")
class AdminMetricsController(private val metrics: MetricsService, private val traffic: TrafficRollupService) {
    @Operation(summary = "Service, JVM, database and request metrics in one read")
    @GetMapping
    fun metrics(): AdminMetricsDto = metrics.snapshot()

    @Operation(summary = "API traffic over the last 24 hours, 7 days or 30 days (range: 24h, 7d, 30d)")
    @GetMapping("/traffic")
    fun traffic(@RequestParam(defaultValue = "24h") range: String): TrafficRangeDto = traffic.range(TrafficRange.of(range))
}
