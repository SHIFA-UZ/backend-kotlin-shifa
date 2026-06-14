package com.shifa.web

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.server.ResponseStatusException

@ControllerAdvice
class ErrorHandler(
    @Value("\${spring.profiles.active:}") private val activeProfile: String
) {
    private val log = LoggerFactory.getLogger(ErrorHandler::class.java)

    // SECURITY (NEW): Reject invalid input early with 400; do not leak field paths or internal details in production
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        val errors = e.bindingResult.fieldErrors.associate { err ->
            err.field to (err.defaultMessage ?: "Invalid value")
        }
        log.debug("Validation failed: {}", errors)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            mapOf(
                "code" to "VALIDATION",
                "error" to 400,
                "message" to "Validation failed",
                "status" to 400,
                "errors" to errors
            )
        )
    }

    // SECURITY (NEW): Return JSON 403 for forbidden (e.g. wrong role); avoid leaking stack/details
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<Map<String, Any>> {
        log.warn("Access denied: {}", e.message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            mapOf(
                "error" to 403,
                "message" to "Access denied",
                "status" to 403
            )
        )
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(e: ResponseStatusException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(e.statusCode)
            .body(mapOf(
                "error" to e.statusCode.value(),
                "message" to (e.reason ?: "An error occurred"),
                "status" to e.statusCode.value()
            ))
    }

    /** Return 400 for IllegalArgumentException (e.g. schedule validation: past dates, too many slots). */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<Map<String, Any>> {
        log.debug("Bad request: {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf(
                "error" to 400,
                "message" to (e.message ?: "Invalid request"),
                "status" to 400
            ))
    }

    /** Business conflict (e.g. duplicate account) — avoid 500 + full stack trace. */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<Map<String, Any>> {
        log.warn("Conflict: {}", e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf(
                "error" to 409,
                "message" to (e.message ?: "Conflict"),
                "status" to 409
            ))
    }

    /** Unique constraint / FK violations — return 409 without expensive ERROR logging. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(e: DataIntegrityViolationException): ResponseEntity<Map<String, Any>> {
        log.warn("Data integrity violation: {}", e.mostSpecificCause.message ?: e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf(
                "error" to 409,
                "message" to "A record with the same unique value already exists",
                "status" to 409
            ))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<Map<String, Any>> {
        // SECURITY: In production we still do NOT leak the exception message
        // (it may contain SQL fragments, file paths, PII, etc.), but the
        // exception class name alone is safe and dramatically improves
        // diagnosability when only the client log is available (e.g.
        // "Internal server error: DataIntegrityViolationException").
        val isProd = activeProfile.contains("prod")
        if (isProd) {
            log.error(
                "Unhandled exception {} (message not exposed to client): {}",
                e.javaClass.simpleName,
                e.message,
                e,
            )
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf(
                    "error" to 500,
                    "message" to "Internal server error: ${e.javaClass.simpleName}",
                    "exceptionClass" to e.javaClass.simpleName,
                    "status" to 500
                ))
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf(
                "error" to 500,
                "message" to (e.message ?: "Internal server error"),
                "exceptionClass" to e.javaClass.simpleName,
                "status" to 500
            ))
    }
}
