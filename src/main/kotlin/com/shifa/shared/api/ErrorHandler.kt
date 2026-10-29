package com.shifa.shared.api

import com.shifa.shared.ErrorBodies.body
import com.shifa.shared.ErrorCode
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

@ControllerAdvice
class ErrorHandler(
    /** Put exception messages in 500 bodies. True only locally; deployed profiles return the class name alone. */
    @Value("\${app.expose-error-details:false}") private val exposeErrorDetails: Boolean,
) {
    private val log = LoggerFactory.getLogger(ErrorHandler::class.java)

    // SECURITY (NEW): Reject invalid input early with 400; do not leak field paths or internal details in production
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        val errors = e.bindingResult.fieldErrors.associate { err ->
            err.field to (err.defaultMessage ?: "Invalid value")
        }
        log.debug("Validation failed: {}", errors)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(body(400, "Validation failed", ErrorCode.VALIDATION, mapOf("errors" to errors)))
    }

    /** Malformed or missing JSON body: 400, not 500. The parser message can echo the payload, so it stays out of the response. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ResponseEntity<Map<String, Any>> {
        log.debug("Unreadable request body: {}", e.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(body(400, "Malformed request body", ErrorCode.MALFORMED_BODY))
    }

    // SECURITY (NEW): Return JSON 403 for forbidden (e.g. wrong role); avoid leaking stack/details
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<Map<String, Any>> {
        log.warn("Access denied: {}", e.message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(403, "Access denied", ErrorCode.FORBIDDEN))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(e: ResponseStatusException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(e.statusCode)
            .body(body(e.statusCode.value(), e.reason ?: "An error occurred", ErrorCode.forStatus(e.statusCode.value())))
    }

    /** Return 400 for IllegalArgumentException (e.g. schedule validation: past dates, too many slots). */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<Map<String, Any>> {
        log.debug("Bad request: {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(body(400, e.message ?: "Invalid request", ErrorCode.BAD_REQUEST))
    }

    /** Business conflict (e.g. duplicate account) — avoid 500 + full stack trace. */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<Map<String, Any>> {
        log.warn("Conflict: {}", e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(409, e.message ?: "Conflict", ErrorCode.CONFLICT))
    }

    /** Unique constraint / FK violations — return 409 without expensive ERROR logging. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(e: DataIntegrityViolationException): ResponseEntity<Map<String, Any>> {
        log.warn("Data integrity violation: {}", e.mostSpecificCause.message ?: e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(body(409, "A record with the same unique value already exists", ErrorCode.CONFLICT))
    }

    /** Missing static file (photo, document, attachment) or unknown path: 404, not 500. */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(e: NoResourceFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(body(404, "Not found", ErrorCode.NOT_FOUND))

    /** Client closed SSE/HTTP before the response finished — not a server fault. */
    @ExceptionHandler(
        AsyncRequestNotUsableException::class,
        ClientAbortException::class,
    )
    fun handleClientAbort(e: Exception): ResponseEntity<Void>? {
        if (SseIoSupport.isClientAbort(e)) {
            log.debug("Client disconnected before response completed: {}", e.message)
            return null
        }
        throw e
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<Map<String, Any>>? {
        if (SseIoSupport.isClientAbort(e)) {
            log.debug("Client disconnected: {}", e.message)
            return null
        }
        // SECURITY: deployed profiles do NOT leak the exception message
        // (it may contain SQL fragments, file paths, PII, etc.), but the
        // exception class name alone is safe and dramatically improves
        // diagnosability when only the client log is available (e.g.
        // "Internal server error: DataIntegrityViolationException").
        if (!exposeErrorDetails) {
            log.error(
                "Unhandled exception {} (message not exposed to client): {}",
                e.javaClass.simpleName,
                e.message,
                e,
            )
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(500, "Internal server error: ${e.javaClass.simpleName}", ErrorCode.INTERNAL, mapOf("exceptionClass" to e.javaClass.simpleName)))
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(body(500, e.message ?: "Internal server error", ErrorCode.INTERNAL, mapOf("exceptionClass" to e.javaClass.simpleName)))
    }
}
