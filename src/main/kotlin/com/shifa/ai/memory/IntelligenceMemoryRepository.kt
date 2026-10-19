package com.shifa.ai.memory

import com.shifa.service.ClinicalRagJdbcRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * JDBC access to `intelligence_memories`. Every read is parameterized by the authenticated
 * doctor id (and that doctor's current clinic for CLINIC rows); there is no unscoped query.
 */
@Repository
class IntelligenceMemoryRepository(
    private val jdbc: JdbcTemplate,
) {
    private val mapper = RowMapper<IntelligenceMemory> { rs: ResultSet, _ ->
        IntelligenceMemory(
            id = rs.getLong("id"),
            scope = IntelligenceMemoryScope.valueOf(rs.getString("scope_type")),
            ownerDoctorId = rs.getObject("owner_doctor_id")?.let { (it as Number).toLong() },
            clinicId = rs.getObject("clinic_id")?.let { (it as Number).toLong() },
            type = IntelligenceMemoryType.valueOf(rs.getString("memory_type")),
            content = rs.getString("content"),
            contentKey = rs.getString("content_key"),
            confidence = rs.getDouble("confidence"),
            status = IntelligenceMemoryStatus.valueOf(rs.getString("status")),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
        )
    }

    private val columns =
        "id, scope_type, owner_doctor_id, clinic_id, memory_type, content, content_key, confidence, status, created_at, expires_at"

    /** Visibility predicate: this doctor's rows, plus CLINIC rows of their current practice clinic. */
    private fun scopeSql(clinicId: Long?): String =
        if (clinicId != null) {
            "((scope_type = 'DOCTOR' AND owner_doctor_id = ?) OR (scope_type = 'CLINIC' AND clinic_id = ?))"
        } else {
            "(scope_type = 'DOCTOR' AND owner_doctor_id = ?)"
        }

    private fun scopeArgs(doctorId: Long, clinicId: Long?): Array<Any> =
        if (clinicId != null) arrayOf(doctorId, clinicId) else arrayOf(doctorId)

    fun listActive(doctorId: Long, clinicId: Long?, limit: Int = 200): List<IntelligenceMemory> =
        jdbc.query(
            """
            SELECT $columns FROM intelligence_memories
            WHERE ${scopeSql(clinicId)} AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY created_at DESC LIMIT ?
            """.trimIndent(),
            mapper,
            *scopeArgs(doctorId, clinicId), limit,
        )

    fun activeOfTypes(doctorId: Long, clinicId: Long?, types: Collection<IntelligenceMemoryType>, limit: Int): List<IntelligenceMemory> {
        if (types.isEmpty()) return emptyList()
        val placeholders = types.joinToString(",") { "?" }
        return jdbc.query(
            """
            SELECT $columns FROM intelligence_memories
            WHERE ${scopeSql(clinicId)} AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
              AND memory_type IN ($placeholders)
            ORDER BY created_at DESC LIMIT ?
            """.trimIndent(),
            mapper,
            *scopeArgs(doctorId, clinicId), *types.map { it.name }.toTypedArray(), limit,
        )
    }

    fun nearest(doctorId: Long, clinicId: Long?, query: FloatArray, limit: Int): List<Pair<IntelligenceMemory, Double>> {
        val vec = ClinicalRagJdbcRepository.toPgVector(query)
        return jdbc.query(
            """
            SELECT $columns, (embedding <=> ?::vector) AS distance FROM intelligence_memories
            WHERE ${scopeSql(clinicId)} AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > NOW())
              AND embedding IS NOT NULL
            ORDER BY embedding <=> ?::vector LIMIT ?
            """.trimIndent(),
            { rs, i -> mapper.mapRow(rs, i)!! to rs.getDouble("distance") },
            vec, *scopeArgs(doctorId, clinicId), vec, limit,
        )
    }

    fun findActiveByKey(doctorId: Long, contentKey: String): IntelligenceMemory? =
        jdbc.query(
            """
            SELECT $columns FROM intelligence_memories
            WHERE scope_type = 'DOCTOR' AND owner_doctor_id = ? AND content_key = ? AND status = 'ACTIVE'
            LIMIT 1
            """.trimIndent(),
            mapper, doctorId, contentKey,
        ).firstOrNull()

    @Transactional
    fun insertDoctorMemory(
        doctorId: Long,
        candidate: IntelligenceMemoryCandidate,
        embedding: FloatArray?,
        sourceRequestId: String?,
        expiresAt: OffsetDateTime?,
    ): Long {
        val id = jdbc.queryForObject(
            """
            INSERT INTO intelligence_memories
              (scope_type, owner_doctor_id, memory_type, content, content_key, confidence, source_request_id, embedding, expires_at)
            VALUES ('DOCTOR', ?, ?, ?, ?, ?, ?, ?::vector, ?)
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            doctorId, candidate.type.name, candidate.content, candidate.contentKey, candidate.confidence.toFloat(),
            sourceRequestId, embedding?.let { ClinicalRagJdbcRepository.toPgVector(it) }, expiresAt,
        )!!
        return id
    }

    /**
     * Replace an ACTIVE fact with a new one atomically: mark old SUPERSEDED first (frees the
     * partial unique index on (doctor, key)), insert the new row, then link `superseded_by`.
     */
    @Transactional
    fun supersedeWithNew(
        doctorId: Long,
        oldId: Long,
        candidate: IntelligenceMemoryCandidate,
        embedding: FloatArray?,
        sourceRequestId: String?,
    ): Long {
        jdbc.update(
            """
            UPDATE intelligence_memories SET status = 'SUPERSEDED', updated_at = NOW()
            WHERE id = ? AND owner_doctor_id = ? AND status = 'ACTIVE'
            """.trimIndent(),
            oldId, doctorId,
        )
        val newId = insertDoctorMemory(doctorId, candidate, embedding, sourceRequestId, null)
        jdbc.update(
            "UPDATE intelligence_memories SET superseded_by = ? WHERE id = ? AND owner_doctor_id = ?",
            newId, oldId, doctorId,
        )
        return newId
    }

    /** Soft delete; only the owning doctor can delete a DOCTOR row. Returns affected rows. */
    fun deleteOwned(doctorId: Long, id: Long): Int =
        jdbc.update(
            """
            UPDATE intelligence_memories SET status = 'DELETED', updated_at = NOW()
            WHERE id = ? AND scope_type = 'DOCTOR' AND owner_doctor_id = ? AND status <> 'DELETED'
            """.trimIndent(),
            id, doctorId,
        )

    fun deleteAllOwned(doctorId: Long): Int =
        jdbc.update(
            """
            UPDATE intelligence_memories SET status = 'DELETED', updated_at = NOW()
            WHERE scope_type = 'DOCTOR' AND owner_doctor_id = ? AND status <> 'DELETED'
            """.trimIndent(),
            doctorId,
        )
}
