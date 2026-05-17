package com.shifa.service

import com.shifa.ai.MedicalPromptBuilder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ClinicalRagRetrievalService(
    private val embeddings: OpenAiEmbeddingService,
    private val jdbcRepository: ClinicalRagJdbcRepository,
    private val access: DoctorPatientAccessService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun buildRetrievalSystemPrompt(doctorId: Long, patientId: Long?, latestUserMessage: String): String {
        if (patientId == null || latestUserMessage.isBlank()) return ""
        if (!access.doctorMayAccessPatient(doctorId, patientId)) return ""

        return try {
            val query = latestUserMessage.trim()
            val queryEmbedding = embeddings.embedTexts(listOf(query)).firstOrNull() ?: return ""
            val hits = jdbcRepository.searchNearestCosine(
                patientId = patientId,
                doctorId = doctorId,
                queryEmbedding = queryEmbedding,
                limit = RETRIEVAL_LIMIT,
            )
            if (hits.isEmpty()) return ""

            runCatching {
                jdbcRepository.recordRetrievalAudit(
                    doctorId = doctorId,
                    patientId = patientId,
                    queryExcerpt = query.take(512),
                    chunkIds = hits.map { it.id }.toLongArray(),
                    distances = hits.map { it.distance }.toDoubleArray(),
                )
            }.onFailure { ex ->
                log.debug("clinical RAG audit write failed: {}", ex.message)
            }

            MedicalPromptBuilder.clinicalRagContextPrompt(hits)
        } catch (ex: Exception) {
            log.warn("clinical RAG retrieval failed: {}", ex.message)
            ""
        }
    }

    companion object {
        private const val RETRIEVAL_LIMIT = 18
    }
}
