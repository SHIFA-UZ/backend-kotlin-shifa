package com.shifa.service

import com.shifa.clinical.ClinicalRagSearchHit
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.util.Locale

@Repository
class ClinicalRagJdbcRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    fun deleteBySource(patientId: Long, sourceType: String, sourceRecordId: Long) {
        jdbcTemplate.update(
            """
            DELETE FROM clinical_rag_chunks
            WHERE patient_id = ? AND source_type = ? AND source_record_id = ?
            """.trimIndent(),
            patientId,
            sourceType,
            sourceRecordId,
        )
    }

    fun insertChunks(
        patientId: Long,
        sourceType: String,
        sourceRecordId: Long,
        chunks: List<Pair<String, FloatArray>>,
    ) {
        if (chunks.isEmpty()) return
        val sql = """
            INSERT INTO clinical_rag_chunks (patient_id, source_type, source_record_id, chunk_index, content_text, embedding)
            VALUES (?, ?, ?, ?, ?, ?::vector)
        """.trimIndent()
        jdbcTemplate.batchUpdate(sql, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                val (text, embedding) = chunks[i]
                ps.setLong(1, patientId)
                ps.setString(2, sourceType)
                ps.setLong(3, sourceRecordId)
                ps.setInt(4, i)
                ps.setString(5, text)
                ps.setObject(6, toPgVector(embedding))
            }

            override fun getBatchSize(): Int = chunks.size
        })
    }

    fun searchNearestCosine(
        patientId: Long,
        doctorId: Long,
        queryEmbedding: FloatArray,
        limit: Int,
    ): List<ClinicalRagSearchHit> {
        val vec = toPgVector(queryEmbedding)
        val sql = """
            SELECT c.id, c.source_type, c.content_text, (c.embedding <=> ?::vector) AS distance
            FROM clinical_rag_chunks c
            WHERE c.patient_id = ?
              AND (
                (c.source_type = 'CONSULTATION_NOTE' AND EXISTS (
                      SELECT 1 FROM consultation_notes cn
                      WHERE cn.id = c.source_record_id AND cn.doctor_id = ?
                    ))
                OR (c.source_type = 'APPOINTMENT_DENTAL' AND EXISTS (
                      SELECT 1 FROM appointments a
                      WHERE a.id = c.source_record_id AND a.doctor_id = ?
                    ))
                OR (c.source_type = 'FORM_0252' AND EXISTS (
                      SELECT 1 FROM patient_forms pf
                      WHERE pf.id = c.source_record_id AND pf.template_id = '025-2'
                        AND pf.created_by_doctor_id = ?
                    ))
                OR (c.source_type NOT IN ('CONSULTATION_NOTE', 'APPOINTMENT_DENTAL', 'FORM_0252'))
              )
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
        """.trimIndent()
        return jdbcTemplate.query(
            sql,
            { rs, _ ->
                ClinicalRagSearchHit(
                    id = rs.getLong("id"),
                    sourceType = rs.getString("source_type"),
                    contentText = rs.getString("content_text"),
                    distance = rs.getDouble("distance"),
                )
            },
            vec,
            patientId,
            doctorId,
            doctorId,
            doctorId,
            vec,
            limit,
        )
    }

    fun recordRetrievalAudit(
        doctorId: Long,
        patientId: Long,
        queryExcerpt: String,
        chunkIds: LongArray,
        distances: DoubleArray,
    ) {
        jdbcTemplate.execute(ConnectionCallback { conn ->
            conn.prepareStatement(
                """
                INSERT INTO clinical_rag_retrieval_audit (doctor_id, patient_id, query_excerpt, chunk_ids, distances)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, doctorId)
                ps.setLong(2, patientId)
                ps.setString(3, queryExcerpt)
                ps.setArray(
                    4,
                    conn.createArrayOf(
                        "bigint",
                        chunkIds.map { java.lang.Long.valueOf(it) }.toTypedArray()
                    )
                )
                ps.setArray(
                    5,
                    conn.createArrayOf(
                        "float8",
                        distances.map { java.lang.Double(it) }.toTypedArray()
                    )
                )
                ps.executeUpdate()
            }
            null
        })
    }

    companion object {
        fun toVectorLiteral(embedding: FloatArray): String =
            embedding.joinToString(prefix = "[", postfix = "]") { v ->
                String.format(Locale.US, "%.8f", v)
            }

        fun toPgVector(embedding: FloatArray): PGobject =
            PGobject().apply {
                type = "vector"
                value = toVectorLiteral(embedding)
            }
    }
}
