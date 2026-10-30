package com.shifa.ai.infrastructure

import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class TrustedAppointmentReference(
    val appointmentId: Long,
    val patientId: Long,
    val patientName: String,
    val localDate: LocalDate,
    val localTime: LocalTime,
    val startAt: Instant,
    val status: String,
)

@Service
class IntelligenceToolReferenceService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional
    fun storeSchedule(
        doctorId: Long,
        conversationId: String?,
        appointments: List<Map<String, Any?>>,
        toolName: String = "get_today_schedule",
    ) {
        val conversation = validConversationId(conversationId) ?: return
        val resultSetId = UUID.randomUUID()
        val now = clock.instant()
        appointments.forEach { row ->
            val appointmentId = (row["appointmentId"] as? Number)?.toLong() ?: return@forEach
            val patientId = (row["patientId"] as? Number)?.toLong() ?: return@forEach
            val startAt = row["startAt"]?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return@forEach
            val localDate = row["date"]?.toString()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: startAt.atOffset(java.time.ZoneOffset.UTC).toLocalDate()
            val localTime = row["startLocal"]?.toString()?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                ?: return@forEach
            val safePayload = mapOf(
                "patientName" to row["patientName"]?.toString().orEmpty(),
                "startAt" to startAt.toString(),
                "isVideo" to (row["isVideo"] == true),
            )
            jdbc.update(
                """
                INSERT INTO intelligence_tool_references
                  (doctor_id, conversation_id, result_set_id, tool_name, reference_type,
                   entity_id, patient_id, local_date, local_time, status, payload_json, expires_at)
                VALUES (?, ?, ?, ?, 'APPOINTMENT', ?, ?, ?, ?, ?, ?::jsonb, ?)
                """.trimIndent(),
                doctorId,
                conversation,
                resultSetId,
                toolName,
                appointmentId,
                patientId,
                localDate,
                localTime,
                row["status"]?.toString(),
                objectMapper.writeValueAsString(safePayload),
                Timestamp.from(now.plus(REFERENCE_TTL)),
            )
        }
        LOGGER.info(
            "intelligence_tool_refs stored conversationId={} tool={} count={} resultSetId={}",
            conversation,
            toolName,
            appointments.size,
            resultSetId,
        )
    }

    fun latestSchedule(
        doctorId: Long,
        conversationId: String?,
    ): List<TrustedAppointmentReference> {
        val conversation = validConversationId(conversationId) ?: return emptyList()
        val rows = jdbc.queryForList(
            """
            SELECT result_set_id, entity_id, patient_id, local_date, local_time, status, payload_json
            FROM intelligence_tool_references
            WHERE doctor_id = ? AND conversation_id = ? AND reference_type = 'APPOINTMENT'
              AND expires_at > ?
            ORDER BY created_at DESC, id ASC
            LIMIT 100
            """.trimIndent(),
            doctorId,
            conversation,
            Timestamp.from(clock.instant()),
        )
        val latestSet = rows.firstOrNull()?.get("result_set_id")?.toString() ?: return emptyList()
        return rows.filter { it["result_set_id"].toString() == latestSet }.mapNotNull { row ->
            @Suppress("UNCHECKED_CAST")
            val payload = runCatching {
                objectMapper.readValue(row["payload_json"].toString(), Map::class.java) as Map<String, Any?>
            }.getOrNull() ?: return@mapNotNull null
            TrustedAppointmentReference(
                appointmentId = (row["entity_id"] as Number).toLong(),
                patientId = (row["patient_id"] as Number).toLong(),
                patientName = payload["patientName"]?.toString().orEmpty(),
                localDate = when (val value = row["local_date"]) {
                    is LocalDate -> value
                    is java.sql.Date -> value.toLocalDate()
                    else -> LocalDate.parse(value.toString())
                },
                localTime = when (val value = row["local_time"]) {
                    is LocalTime -> value
                    is java.sql.Time -> value.toLocalTime()
                    else -> LocalTime.parse(value.toString())
                },
                startAt = Instant.parse(payload["startAt"].toString()),
                status = row["status"]?.toString().orEmpty(),
            )
        }
    }

    fun resolveAppointment(
        doctorId: Long,
        conversationId: String?,
        appointmentId: Long?,
        patientName: String?,
        sourceDate: LocalDate?,
        sourceTime: LocalTime?,
    ): List<TrustedAppointmentReference> {
        var candidates = latestSchedule(doctorId, conversationId)
        if (appointmentId != null) candidates = candidates.filter { it.appointmentId == appointmentId }
        patientName?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val wanted = normalize(raw)
            candidates = candidates.filter {
                val candidate = normalize(it.patientName)
                candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate) ||
                    similarity(candidate, wanted) >= 0.72
            }
        }
        if (sourceDate != null) candidates = candidates.filter { it.localDate == sourceDate }
        if (sourceTime != null) candidates = candidates.filter { it.localTime == sourceTime }
        LOGGER.info(
            "intelligence_reference_resolution conversationId={} type=APPOINTMENT suppliedId={} " +
                "hasPatientName={} sourceDate={} sourceTime={} matches={}",
            validConversationId(conversationId),
            appointmentId,
            !patientName.isNullOrBlank(),
            sourceDate,
            sourceTime,
            candidates.size,
        )
        return candidates
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun similarity(left: String, right: String): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        left.forEachIndexed { i, a ->
            current[0] = i + 1
            right.forEachIndexed { j, b ->
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a == b) 0 else 1,
                )
            }
            current.copyInto(previous)
        }
        return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
    }

    private fun validConversationId(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }

    companion object {
        private val REFERENCE_TTL = Duration.ofMinutes(30)
        private val LOGGER = LoggerFactory.getLogger(IntelligenceToolReferenceService::class.java)
    }
}
