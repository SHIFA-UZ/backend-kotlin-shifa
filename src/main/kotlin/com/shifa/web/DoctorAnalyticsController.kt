package com.shifa.web

import com.shifa.security.DoctorPrincipal
import com.shifa.service.DoctorAnalyticsService
import com.shifa.web.dto.DoctorSmsUsageDto
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import java.time.LocalDate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

/**
 * Doctor-scoped analytics API. Authenticated doctors only; aggregate data, no PII.
 * TODO: Add feature flag to hide analytics when no data; date range enum (LAST_7_DAYS, LAST_30_DAYS)
 * for future expansion; navigation stub for full "Analytics" screen.
 */
@RestController
@RequestMapping("/api/doctor/analytics")
class DoctorAnalyticsController(
    private val analytics: DoctorAnalyticsService
) {

    @GetMapping("/overview")
    fun overview(@AuthenticationPrincipal principal: DoctorPrincipal) =
        principal.profile?.let { doctor ->
            ResponseEntity.ok(analytics.overview(doctor))
        } ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Doctor profile not found")

    @GetMapping("/appointments-trend")
    fun appointmentsTrend(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam(defaultValue = "7") days: Int
    ) =
        principal.profile?.let { doctor ->
            val safeDays = days.coerceIn(1, 90)
            ResponseEntity.ok(analytics.appointmentsTrend(doctor, safeDays))
        } ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Doctor profile not found")

    @GetMapping("/consultation-types")
    fun consultationTypes(@AuthenticationPrincipal principal: DoctorPrincipal) =
        principal.profile?.let { doctor ->
            ResponseEntity.ok(analytics.consultationTypes(doctor))
        } ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Doctor profile not found")

    @GetMapping("/engagement")
    fun engagement(@AuthenticationPrincipal principal: DoctorPrincipal) =
        principal.profile?.let { doctor ->
            ResponseEntity.ok(analytics.engagement(doctor))
        } ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Doctor profile not found")

    @GetMapping("/sms-usage")
    fun smsUsage(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<DoctorSmsUsageDto> =
        principal.profile?.let { doctor ->
            ResponseEntity.ok(analytics.smsUsage(doctor, from, to))
        } ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Doctor profile not found")
}
