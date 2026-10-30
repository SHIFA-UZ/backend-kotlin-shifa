package com.shifa.appointments.infrastructure

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingCancellation(
    val token: String,
    val doctorId: Long,
    val appointmentIds: List<Long>,
    val fingerprints: Map<Long, String>,
    val createdAt: Instant,
    val conversationId: String = "legacy",
)

@Component
class PendingAppointmentActionStore(
    private val clock: Clock,
    private val jdbc: JdbcTemplate? = null,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val testFallback = ConcurrentHashMap<Pair<Long, String>, PendingCancellation>()

    @Transactional
    fun save(value: PendingCancellation) {
        if (jdbc == null) {
            testFallback[value.doctorId to validConversation(value.conversationId)] = value
            return
        }
        clear(value.doctorId, value.conversationId)
        jdbc.update(
            """
            INSERT INTO intelligence_pending_actions
              (doctor_id, conversation_id, action_type, token, payload_json, expires_at)
            VALUES (?, ?, 'CANCEL', ?, ?::jsonb, ?)
            """.trimIndent(),
            value.doctorId,
            validConversation(value.conversationId),
            value.token,
            objectMapper.writeValueAsString(
                mapOf(
                    "appointmentIds" to value.appointmentIds,
                    "fingerprints" to value.fingerprints,
                ),
            ),
            Timestamp.from(value.createdAt.plus(TTL)),
        )
    }

    fun get(doctorId: Long, conversationId: String = "legacy"): PendingCancellation? {
        if (jdbc == null) {
            val key = doctorId to validConversation(conversationId)
            val value = testFallback[key] ?: return null
            if (value.createdAt.plus(TTL).isBefore(clock.instant())) {
                testFallback.remove(key)
                return null
            }
            return value
        }
        val row = jdbc.queryForList(
            """
            SELECT token, payload_json, created_at
            FROM intelligence_pending_actions
            WHERE doctor_id = ? AND conversation_id = ? AND action_type = 'CANCEL'
              AND consumed_at IS NULL AND expires_at > ?
            ORDER BY created_at DESC LIMIT 1
            """.trimIndent(),
            doctorId,
            validConversation(conversationId),
            Timestamp.from(clock.instant()),
        ).firstOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(
            row["payload_json"].toString(),
            object : TypeReference<Map<String, Any?>>() {},
        )
        val appointmentIds = (payload["appointmentIds"] as? List<*>)
            .orEmpty().mapNotNull { (it as? Number)?.toLong() }
        val fingerprints = (payload["fingerprints"] as? Map<*, *>)
            .orEmpty().mapNotNull { (key, value) ->
                key?.toString()?.toLongOrNull()?.let { it to value.toString() }
            }.toMap()
        return PendingCancellation(
            token = row["token"].toString(),
            doctorId = doctorId,
            appointmentIds = appointmentIds,
            fingerprints = fingerprints,
            createdAt = (row["created_at"] as Timestamp).toInstant(),
            conversationId = validConversation(conversationId),
        )
    }

    fun clear(doctorId: Long, conversationId: String = "legacy") {
        if (jdbc == null) {
            testFallback.remove(doctorId to validConversation(conversationId))
            return
        }
        jdbc.update(
            """
            UPDATE intelligence_pending_actions
            SET consumed_at = NOW()
            WHERE doctor_id = ? AND conversation_id = ? AND action_type = 'CANCEL'
              AND consumed_at IS NULL
            """.trimIndent(),
            doctorId,
            validConversation(conversationId),
        )
    }

    fun newToken(): String = UUID.randomUUID().toString()

    private fun validConversation(value: String): String =
        value.trim().ifEmpty { "legacy" }.take(128)

    companion object {
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
