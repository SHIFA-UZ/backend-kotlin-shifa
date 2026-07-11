package com.shifa.clinicalengine.service

import com.shifa.clinicalengine.repo.*
import com.shifa.clinicalengine.web.dto.*
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ClinicalEngineService(
    private val catalogCache: ClinicalEngineCatalogCache,
    private val diseases: ClinicalDiseaseRepository,
    private val chips: ClinicalChipRepository,
    private val chipI18n: ClinicalChipI18nRepository,
    private val diseaseChips: ClinicalDiseaseChipRepository,
) {

    @Transactional(readOnly = true)
    fun listGroups(): List<ClinicalGroupDto> = catalogCache.listGroups()

    @Transactional(readOnly = true)
    fun listDiseasesByGroup(groupId: String): List<ClinicalDiseaseSummaryDto> =
        diseases.findByGroupIdAndActiveTrueOrderByNumberValAsc(groupId).map { toSummary(it) }

    @Transactional(readOnly = true)
    fun getDisease(diseaseId: String): ClinicalDiseaseDetailDto {
        val disease = diseases.findById(diseaseId).orElseThrow { IllegalArgumentException("Disease not found: $diseaseId") }
        val links = diseaseChips.findByIdDiseaseIdOrderBySortOrderAsc(diseaseId)
        val chipIds = links.map { it.id.chipId }
        val chipEntities = chips.findAllById(chipIds).associateBy { it.chipId }
        val i18n = chipI18n.findByIdChipIdIn(chipIds)
        val i18nByChip = i18n.groupBy { it.id.chipId }

        val chipDtos = links.mapNotNull { link ->
            val chip = chipEntities[link.id.chipId] ?: return@mapNotNull null
            val labels = i18nByChip[chip.chipId].orEmpty()
            ClinicalChipDto(
                chipId = chip.chipId,
                field = chip.fieldName,
                labels = ClinicalLocalizedNameDto(
                    ru = labels.find { it.id.locale == "ru" }?.label ?: "",
                    uz = labels.find { it.id.locale == "uz" }?.label ?: "",
                    en = labels.find { it.id.locale == "en" }?.label ?: "",
                ),
                variables = chip.variables,
                priority = chip.priority,
            )
        }

        return ClinicalDiseaseDetailDto(
            diseaseId = disease.diseaseId,
            number = disease.numberVal,
            groupId = disease.groupId,
            slug = disease.slug,
            icdCodes = disease.icdCodes,
            names = ClinicalLocalizedNameDto(disease.nameRu, disease.nameUz, disease.nameEn),
            chips = chipDtos,
        )
    }

    @Transactional(readOnly = true)
    fun searchChips(query: String, locale: String, limit: Int = 20): List<ClinicalChipDto> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val capped = limit.coerceIn(1, 30)
        val normalizedLocale = normalizeLocale(locale)
        val matches = chipI18n.searchByLocaleAndLabel(
            normalizedLocale,
            q,
            PageRequest.of(0, capped),
        )
        if (matches.isEmpty()) return emptyList()
        val chipIds = matches.map { it.id.chipId }.toSet()
        val chipMap = chips.findAllById(chipIds).associateBy { it.chipId }
        val allI18n = chipI18n.findByIdChipIdIn(chipIds).groupBy { it.id.chipId }

        return matches.mapNotNull { row ->
            val chip = chipMap[row.id.chipId] ?: return@mapNotNull null
            val labels = allI18n[chip.chipId].orEmpty()
            ClinicalChipDto(
                chipId = chip.chipId,
                field = chip.fieldName,
                labels = ClinicalLocalizedNameDto(
                    ru = labels.find { it.id.locale == "ru" }?.label ?: "",
                    uz = labels.find { it.id.locale == "uz" }?.label ?: "",
                    en = labels.find { it.id.locale == "en" }?.label ?: "",
                ),
                variables = chip.variables,
                priority = chip.priority,
            )
        }
    }

    @Transactional(readOnly = true)
    fun listOcclusionChips(): List<ClinicalOcclusionChipDto> = catalogCache.listOcclusionChips()

    @Transactional(readOnly = true)
    fun listSharedTemplates(type: String): List<ClinicalSharedTemplateDto> =
        catalogCache.listSharedTemplates(type)

    private fun toSummary(disease: com.shifa.clinicalengine.domain.ClinicalDisease) =
        ClinicalDiseaseSummaryDto(
            diseaseId = disease.diseaseId,
            number = disease.numberVal,
            groupId = disease.groupId,
            slug = disease.slug,
            icdCodes = disease.icdCodes,
            names = ClinicalLocalizedNameDto(disease.nameRu, disease.nameUz, disease.nameEn),
        )

    private fun normalizeLocale(locale: String): String =
        when (locale.lowercase().take(2)) {
            "uz" -> "uz"
            "en" -> "en"
            else -> "ru"
        }
}
