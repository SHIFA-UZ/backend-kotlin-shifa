package com.shifa.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.AiRateLimitProperties
import com.shifa.domain.Role
import com.shifa.service.AiRateLimitOutcome
import com.shifa.service.AiRateLimitService
import com.shifa.service.DenyReason
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class AiRateLimitFilter(
    private val props: AiRateLimitProperties,
    private val aiRateLimitService: AiRateLimitService,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter(), Ordered {

    override fun getOrder(): Int = 50 // after JwtAuthFilter, before UserRateLimitFilter (100)

    private val log = LoggerFactory.getLogger(javaClass)
    private val matcher = AntPathMatcher()

    private sealed class AiPathKind {
        data object Doctor : AiPathKind()
        data object Patient : AiPathKind()
    }

    companion object {
        private const val METHOD = "POST"
    }

    private val doctorAiPatterns = listOf(
        "/api/ai/stream",
        "/api/ai/transcribe",
        "/api/ai/transcription-feedback",
        "/api/ai/suggest-icd10",
        "/api/ai/patient-briefing/*",
        "/api/ai/clinical-rag/reindex-patient/*",
        "/api/consultations/upload-recording"
    )

    private val patientAiPatterns = listOf(
        "/api/patients/me/copilot/stream",
        "/api/patients/me/copilot/transcribe",
        "/api/patients/me/copilot/transcription-feedback",
        "/api/patients/me/copilot/suggest-doctors-chat",
        "/api/patients/me/copilot/resolve-booking",
        "/api/patients/me/appointments/*/ai-visit-summary:generate",
        "/api/patients/me/appointments/*/ai-visit-summary/ask"
    )

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!props.enabled) return true
        if (!METHOD.equals(request.method, ignoreCase = true)) return true
        val path = request.servletPath?.takeIf { it.isNotBlank() } ?: return true
        if (kindForPath(normalize(path)) == null) return true
        return false
    }

    private fun normalize(path: String): String =
        if (path.endsWith("/") && path.length > 1) path.dropLast(1) else path

    private fun kindForPath(path: String): AiPathKind? {
        if (doctorAiPatterns.any { matcher.match(it, path) }) return AiPathKind.Doctor
        if (patientAiPatterns.any { matcher.match(it, path) }) return AiPathKind.Patient
        return null
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = normalize(request.servletPath ?: "")
        val expectedKind = kindForPath(path)
        if (expectedKind == null) {
            filterChain.doFilter(request, response)
            return
        }

        val auth = SecurityContextHolder.getContext().authentication
        if (auth == null || !auth.isAuthenticated) {
            filterChain.doFilter(request, response)
            return
        }

        val pair = when (val p = auth.principal) {
            is DoctorPrincipal -> p.profile.user.id to Role.DOCTOR
            is PatientPrincipal -> p.user.id to Role.PATIENT
            else -> 0L to null
        }
        val userId = pair.first
        val roleFromPrincipal = pair.second

        if (roleFromPrincipal == null) {
            filterChain.doFilter(request, response)
            return
        }

        val roleMatchesPath = when (expectedKind) {
            AiPathKind.Doctor -> roleFromPrincipal == Role.DOCTOR
            AiPathKind.Patient -> roleFromPrincipal == Role.PATIENT
        }
        if (!roleMatchesPath) {
            filterChain.doFilter(request, response)
            return
        }

        when (val outcome = aiRateLimitService.tryConsume(userId, roleFromPrincipal)) {
            is AiRateLimitOutcome.Allowed -> {
                if (props.enabled && outcome.dailyLimit != Int.MAX_VALUE) {
                    response.setHeader("X-RateLimit-Limit", outcome.dailyLimit.toString())
                    response.setHeader("X-RateLimit-Remaining", outcome.remainingAfter.toString())
                    response.setHeader("X-RateLimit-Reset", outcome.resetAtEpochSeconds.toString())
                }
                filterChain.doFilter(request, response)
            }
            is AiRateLimitOutcome.Denied -> {
                log.warn(
                    "AI rate limit exceeded userId={} role={} path={} reason={}",
                    userId,
                    roleFromPrincipal,
                    path,
                    outcome.reason
                )
                respond429(response, outcome)
            }
        }
    }

    private fun respond429(response: HttpServletResponse, denied: AiRateLimitOutcome.Denied) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = "application/json"
        response.characterEncoding = Charsets.UTF_8.name()
        val zoneId = ZoneId.of(props.timezone)
        val resetIso = Instant.ofEpochSecond(denied.resetAtEpochSeconds)
            .atZone(zoneId)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val now = Instant.now().epochSecond
        val retryDaily = (denied.resetAtEpochSeconds - now).coerceIn(1L, 86_400L)
        response.setHeader("Retry-After", (if (denied.reason == DenyReason.BURST) 60L else retryDaily).toString())
        response.setHeader("X-RateLimit-Limit", denied.dailyLimit.toString())
        response.setHeader("X-RateLimit-Remaining", denied.remaining.toString())
        response.setHeader("X-RateLimit-Reset", denied.resetAtEpochSeconds.toString())
        val message = when (denied.reason) {
            DenyReason.DAILY_QUOTA ->
                "You've reached your daily AI usage limit. Try again after midnight (${props.timezone})."
            DenyReason.BURST ->
                "Too many AI requests this minute. Please wait a minute and try again."
        }
        val body = mapOf(
            "code" to "RATE_LIMIT",
            "message" to message,
            "remaining" to denied.remaining,
            "dailyLimit" to denied.dailyLimit,
            "resetAt" to resetIso,
            "reason" to denied.reason.name
        )
        response.writer.write(objectMapper.writeValueAsString(body))
    }
}
