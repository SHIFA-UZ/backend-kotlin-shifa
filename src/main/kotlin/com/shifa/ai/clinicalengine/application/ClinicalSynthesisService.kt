package com.shifa.ai.clinicalengine.application

import com.shifa.ai.clinicalengine.infrastructure.ClinicalChipI18nRepository
import com.shifa.ai.clinicalengine.infrastructure.ClinicalChipRepository
import com.shifa.ai.clinicalengine.infrastructure.ClinicalChipSynthesisTemplateRepository
import com.shifa.ai.clinicalengine.infrastructure.ClinicalOcclusionChipRepository
import com.shifa.ai.clinicalengine.api.ClinicalChipSelectionDto
import com.shifa.ai.clinicalengine.api.ClinicalSynthesizeResponseDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ClinicalSynthesisService(
    private val chips: ClinicalChipRepository,
    private val chipI18n: ClinicalChipI18nRepository,
    private val synthesisTemplates: ClinicalChipSynthesisTemplateRepository,
    private val occlusionChips: ClinicalOcclusionChipRepository,
    private val catalogCache: ClinicalEngineCatalogCache,
) {

    @Transactional(readOnly = true)
    fun synthesize(locale: String, selections: List<ClinicalChipSelectionDto>): ClinicalSynthesizeResponseDto {
        val normalizedLocale = normalizeLocale(locale)
        val chipIds = selections.map { it.chipId }.filter { !ClinicalEngineCatalogCache.isSharedChipId(it) }.toSet()
        val chipMap = chips.findAllById(chipIds).associateBy { it.chipId }
        val i18nMap = chipI18n.findByIdChipIdIn(chipIds)
            .filter { it.id.locale == normalizedLocale }
            .associateBy { it.id.chipId }
        val templateMap = synthesisTemplates.findByChipIdInAndLocale(chipIds, normalizedLocale)
            .associateBy { it.chipId }
        val occlusionIds = selections.map { it.chipId }.filter { it.startsWith("occ.") }.toSet()
        val occlusionMap = occlusionChips.findAllById(occlusionIds).associateBy { it.chipId }

        val byField = linkedMapOf<String, MutableList<String>>()

        for (selection in selections) {
            if (ClinicalEngineCatalogCache.isSharedChipId(selection.chipId)) {
                appendSharedSentence(byField, selection, normalizedLocale)
                continue
            }

            val chip = chipMap[selection.chipId]
            val occlusion = occlusionMap[selection.chipId]
            val field = chip?.fieldName ?: if (occlusion != null) "occlusion" else continue
            val label = when {
                chip != null -> i18nMap[chip.chipId]?.label
                occlusion != null -> occlusionLabel(occlusion, normalizedLocale)
                else -> null
            } ?: continue

            val variables = chip?.variables ?: occlusion?.variables ?: emptyList()
            val template = templateMap[selection.chipId]?.sentenceTemplate
                ?: ClinicalSynthesisTemplateBuilder.build(field, label, normalizedLocale, variables)

            appendSentence(byField, field, template, label, selection.variables)
        }

        val fields = byField.mapValues { (_, sentences) -> sentences.joinToString(" ") }
        return ClinicalSynthesizeResponseDto(fields = fields)
    }

    private fun appendSharedSentence(
        byField: LinkedHashMap<String, MutableList<String>>,
        selection: ClinicalChipSelectionDto,
        locale: String,
    ) {
        val shared = catalogCache.sharedTemplate(selection.chipId) ?: return
        val label = when (locale) {
            "uz" -> shared.labelUz
            "en" -> shared.labelEn
            else -> shared.labelRu
        }
        val variables = emptyList<String>()
        val sentenceTemplate = ClinicalSynthesisTemplateBuilder.build(shared.fieldName, label, locale, variables)
        appendSentence(byField, shared.fieldName, sentenceTemplate, label, selection.variables)
    }

    private fun appendSentence(
        byField: LinkedHashMap<String, MutableList<String>>,
        field: String,
        template: String,
        label: String,
        variables: Map<String, String>,
    ) {
        val renderedLabel = ClinicalSynthesisTemplateBuilder.renderTemplate(
            label.replace("[X]", "{X}", ignoreCase = true),
            variables,
        )
        val sentence = ClinicalSynthesisTemplateBuilder.renderTemplate(
            template.replace("{text}", renderedLabel),
            variables,
        )
        byField.getOrPut(field) { mutableListOf() }.add(sentence)
    }

    private fun occlusionLabel(
        chip: com.shifa.ai.clinicalengine.domain.ClinicalOcclusionChip,
        locale: String,
    ): String = when (locale) {
        "uz" -> chip.labelUz
        "en" -> chip.labelEn
        else -> chip.labelRu
    }

    private fun normalizeLocale(locale: String): String =
        when (locale.lowercase().take(2)) {
            "uz" -> "uz"
            "en" -> "en"
            else -> "ru"
        }
}
