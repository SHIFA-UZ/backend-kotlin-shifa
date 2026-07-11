package com.shifa.clinicalengine.service

import com.shifa.clinicalengine.domain.ClinicalDoctorDiseaseRecent
import com.shifa.clinicalengine.domain.ClinicalDoctorDiseaseUsage
import com.shifa.clinicalengine.domain.ClinicalDoctorDiseaseUsageId
import com.shifa.clinicalengine.repo.ClinicalDiseaseRepository
import com.shifa.clinicalengine.repo.ClinicalDoctorDiseaseRecentRepository
import com.shifa.clinicalengine.repo.ClinicalDoctorDiseaseUsageRepository
import com.shifa.clinicalengine.web.dto.ClinicalLocalizedNameDto
import com.shifa.clinicalengine.web.dto.ClinicalTopDiagnosisDto
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class ClinicalSmartPriorityService(
    private val usageRepo: ClinicalDoctorDiseaseUsageRepository,
    private val recentRepo: ClinicalDoctorDiseaseRecentRepository,
    private val diseaseRepo: ClinicalDiseaseRepository,
) {
    companion object {
        const val RECENT_LIMIT = 20
    }

    @Transactional(readOnly = true)
    fun topDiagnoses(doctorId: Long, limit: Int = 5): List<ClinicalTopDiagnosisDto> {
        val capped = limit.coerceIn(1, 20)
        val usageRows = usageRepo.findTopByDoctor(doctorId, PageRequest.of(0, capped))
        if (usageRows.isNotEmpty()) {
            val diseaseIds = usageRows.map { it.id.diseaseId }
            val diseases = diseaseRepo.findAllById(diseaseIds).associateBy { it.diseaseId }
            return usageRows.mapNotNull { row ->
                val disease = diseases[row.id.diseaseId] ?: return@mapNotNull null
                ClinicalTopDiagnosisDto(
                    diseaseId = disease.diseaseId,
                    names = ClinicalLocalizedNameDto(disease.nameRu, disease.nameUz, disease.nameEn),
                    icdCodes = disease.icdCodes,
                    useCount = row.useCount,
                    lastUsedAt = row.lastUsedAt.toString(),
                )
            }
        }

        val recent = recentRepo.findByDoctorIdOrderByUsedAtDesc(doctorId, PageRequest.of(0, capped))
        if (recent.isEmpty()) return emptyList()
        val diseaseIds = recent.map { it.diseaseId }.distinct()
        val diseases = diseaseRepo.findAllById(diseaseIds).associateBy { it.diseaseId }
        return recent.mapNotNull { row ->
            val disease = diseases[row.diseaseId] ?: return@mapNotNull null
            ClinicalTopDiagnosisDto(
                diseaseId = disease.diseaseId,
                names = ClinicalLocalizedNameDto(disease.nameRu, disease.nameUz, disease.nameEn),
                icdCodes = disease.icdCodes,
                useCount = 0,
                lastUsedAt = row.usedAt.toString(),
            )
        }.distinctBy { it.diseaseId }.take(capped)
    }

    @Transactional
    fun recordUsage(doctorId: Long, diseaseId: String) {
        if (diseaseId.isBlank()) return
        if (!diseaseRepo.existsById(diseaseId)) return

        val usageId = ClinicalDoctorDiseaseUsageId(doctorId, diseaseId)
        val existing = usageRepo.findById(usageId).orElse(null)
        val now = OffsetDateTime.now()
        if (existing == null) {
            usageRepo.save(
                ClinicalDoctorDiseaseUsage(
                    id = usageId,
                    useCount = 1,
                    lastUsedAt = now,
                )
            )
        } else {
            existing.useCount += 1
            existing.lastUsedAt = now
            usageRepo.save(existing)
        }

        recentRepo.save(
            ClinicalDoctorDiseaseRecent(
                doctorId = doctorId,
                diseaseId = diseaseId,
                usedAt = now,
            )
        )
        trimRecent(doctorId)
    }

    private fun trimRecent(doctorId: Long) {
        val rows = recentRepo.findByDoctorIdOrderByUsedAtDesc(
            doctorId,
            PageRequest.of(0, RECENT_LIMIT + 1),
        )
        if (rows.size <= RECENT_LIMIT) return
        recentRepo.deleteAll(rows.drop(RECENT_LIMIT))
    }
}
