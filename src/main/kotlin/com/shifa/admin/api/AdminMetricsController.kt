package com.shifa.admin.api

import com.shifa.admin.api.dto.AdminMetricsDto
import com.shifa.admin.application.MetricsService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Operations view of the running service for the admin dashboard page (`/admin-metrics/`); the admin routes are ADMIN only. */
@RestController
@RequestMapping("/api/admin/metrics")
class AdminMetricsController(private val metrics: MetricsService) {
    @Operation(summary = "Service, JVM, database and request metrics in one read")
    @GetMapping
    fun metrics(): AdminMetricsDto = metrics.snapshot()
}
