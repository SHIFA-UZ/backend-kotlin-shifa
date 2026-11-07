package com.shifa.shared.api

import com.shifa.shared.ErrorBodies
import com.shifa.shared.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest

/**
 * `Idempotency-Key` on the POSTs that record money (BE-13): the first 2xx answer for a key is stored per account for
 * 24 h and replayed byte for byte to a retry (with `Idempotent-Replayed: true`), so a retry after a timeout never
 * records a payment or a charge twice. Without the header nothing changes. Non-2xx answers are not stored (the retry
 * runs again). Same key with another path or body: 409 `IDEMPOTENCY_KEY_REUSED`; while the first call still runs:
 * 409 `IDEMPOTENCY_IN_PROGRESS`; a malformed key: 400 `IDEMPOTENCY_KEY_INVALID`.
 *
 * Runs after the security chain (order 0 > -100), so only authenticated, authorised calls reach it. A replay skips the
 * controller's clinic and patient checks: it only hands the same account the answer it already got, within 24 h.
 */
class IdempotencyFilter(
    private val store: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || request.getHeader(HEADER) == null || !covers(request.requestURI)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val key = request.getHeader(HEADER).trim()
        val account = (SecurityContextHolder.getContext().authentication?.principal as? UserDetails)?.username
        if (account == null) {
            chain.doFilter(request, response)
            return
        }
        if (!WELL_FORMED.matches(key)) {
            writeError(response, "Idempotency-Key must be 8 to 128 letters, digits, '-' or '_'", ErrorCode.IDEMPOTENCY_KEY_INVALID, 400)
            return
        }
        val body = request.inputStream.readAllBytes()
        val hash = sha256(request.method.toByteArray() + request.requestURI.toByteArray() + 0 + body)
        if (!store.claim(account, key, hash)) {
            answerExisting(store.find(account, key), hash, response)
            return
        }
        val wrapped = ContentCachingResponseWrapper(response)
        // Once the call answered 2xx its write is committed: the key is never released after that, even if storing
        // the answer fails, so a retry gets IDEMPOTENCY_IN_PROGRESS instead of recording the money twice.
        var succeeded = false
        try {
            chain.doFilter(CachedBodyRequest(request, body), wrapped)
            succeeded = wrapped.status in 200..299
            if (succeeded) store.complete(account, key, wrapped.status, wrapped.contentType, wrapped.contentAsByteArray)
        } finally {
            if (!succeeded) store.release(account, key)
            wrapped.copyBodyToResponse()
        }
    }

    private fun answerExisting(row: IdempotencyStore.Row?, hash: String, response: HttpServletResponse) {
        when {
            row != null && row.requestHash != hash ->
                writeError(response, "This Idempotency-Key was used for another request", ErrorCode.IDEMPOTENCY_KEY_REUSED, 409)
            row?.status == null ->
                writeError(response, "A request with this Idempotency-Key is still running", ErrorCode.IDEMPOTENCY_IN_PROGRESS, 409)
            else -> {
                response.status = row.status
                row.contentType?.let { response.contentType = it }
                response.setHeader(REPLAYED_HEADER, "true")
                response.outputStream.write(row.body ?: ByteArray(0))
            }
        }
    }

    private fun writeError(response: HttpServletResponse, message: String, code: ErrorCode, status: Int) {
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write(objectMapper.writeValueAsString(ErrorBodies.body(status, message, code)))
    }

    /** The request with its body already read, so the controller can read it again. */
    private class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val input = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = input.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
                override fun isFinished(): Boolean = input.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener?) = Unit
            }
        }

        override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
    }

    companion object {
        const val HEADER = "Idempotency-Key"
        const val REPLAYED_HEADER = "Idempotent-Replayed"
        private val WELL_FORMED = Regex("[A-Za-z0-9_-]{8,128}")

        /** The POSTs that record a payment or a charge, plus the plan compose that may record one. */
        private val COVERED = listOf(
            Regex("/api/treatment-plans/\\d+/payments"),
            Regex("/api/treatment-plans/compose"),
            Regex("/api/clinics/\\d+/finance/payments"),
            Regex("/api/clinics/\\d+/finance/installment-items/\\d+/mark-paid"),
            Regex("/api/clinics/\\d+/appointments/\\d+/visit-charges"),
        )

        fun covers(path: String): Boolean = COVERED.any { it.matches(path) }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Rows of `idempotency_keys` (V129). A row without a status is a request still running. */
@Component
class IdempotencyStore(private val jdbc: JdbcTemplate) {
    data class Row(val requestHash: String, val status: Int?, val contentType: String?, val body: ByteArray?)

    /** True when this call owns the key now: no row younger than 24 h existed (expired rows are dropped first). */
    fun claim(account: String, key: String, requestHash: String): Boolean {
        // ponytail: a running row is never taken over (the instance may have committed, then died before complete());
        // that key answers IDEMPOTENCY_IN_PROGRESS until it expires and the doctor's next tap sends a new key.
        // Upgrade: write the row inside the business transaction.
        jdbc.update(
            "DELETE FROM idempotency_keys WHERE account_id = ? AND idem_key = ? AND created_at < now() - interval '24 hours'",
            account, key,
        )
        return jdbc.update(
            "INSERT INTO idempotency_keys (account_id, idem_key, request_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            account, key, requestHash,
        ) == 1
    }

    fun find(account: String, key: String): Row? =
        jdbc.query(
            "SELECT request_hash, response_status, response_type, response_body FROM idempotency_keys WHERE account_id = ? AND idem_key = ?",
            { rs, _ -> Row(rs.getString(1), rs.getObject(2) as Int?, rs.getString(3), rs.getBytes(4)) },
            account, key,
        ).firstOrNull()

    fun complete(account: String, key: String, status: Int, contentType: String?, body: ByteArray) {
        jdbc.update(
            "UPDATE idempotency_keys SET response_status = ?, response_type = ?, response_body = ? WHERE account_id = ? AND idem_key = ?",
            status, contentType, body, account, key,
        )
    }

    fun release(account: String, key: String) {
        jdbc.update("DELETE FROM idempotency_keys WHERE account_id = ? AND idem_key = ? AND response_status IS NULL", account, key)
    }

    @Scheduled(fixedRate = 3_600_000)
    @SchedulerLock(name = "idempotency-key-cleanup", lockAtLeastFor = "PT20S")
    fun cleanupExpired() {
        jdbc.update("DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours'")
    }
}

@Configuration
class IdempotencyConfig {
    /** After the security chain (Spring Security's filter is at -100), on the API paths only. */
    @Bean
    fun idempotencyFilterRegistration(store: IdempotencyStore, objectMapper: ObjectMapper): FilterRegistrationBean<IdempotencyFilter> =
        FilterRegistrationBean(IdempotencyFilter(store, objectMapper)).apply {
            addUrlPatterns("/api/*")
            order = 0
        }
}
