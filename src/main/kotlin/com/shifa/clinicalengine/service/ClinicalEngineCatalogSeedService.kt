package com.shifa.clinicalengine.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.clinicalengine.domain.*
import com.shifa.clinicalengine.repo.*
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ClinicalEngineCatalogSeedService(
    private val objectMapper: ObjectMapper,
    private val groups: ClinicalGroupRepository,
    private val diseases: ClinicalDiseaseRepository,
    private val chips: ClinicalChipRepository,
    private val chipI18n: ClinicalChipI18nRepository,
    private val diseaseChips: ClinicalDiseaseChipRepository,
    private val synthesisTemplates: ClinicalChipSynthesisTemplateRepository,
    private val occlusionChips: ClinicalOcclusionChipRepository,
    private val sharedTemplates: ClinicalSharedTemplateRepository,
    private val toothKeys: ClinicalDentalToothKeyRepository,
    private val catalogCache: ClinicalEngineCatalogCache,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun seedIfEmpty() {
        if (groups.count() > 0) {
            log.info("Clinical Engine catalog already seeded ({} groups)", groups.count())
            catalogCache.warm()
            return
        }
        log.info("Seeding Clinical Engine catalog from classpath JSON...")
        seedMasterCatalog()
        seedOcclusionLibrary()
        seedSharedLibraries()
        seedToothKeys()
        catalogCache.warm()
        log.info(
            "Clinical Engine seed complete: {} diseases, {} chips",
            diseases.count(),
            chips.count(),
        )
    }

    private fun seedMasterCatalog() {
        val root = readJson("clinical-engine/MASTER_CATALOG.json")
        for (groupNode in root["groups"]) {
            val names = groupNode["names"]
            groups.save(
                ClinicalGroup(
                    groupId = groupNode["group_id"].asText(),
                    sortOrder = groupNode["sort"].asInt(),
                    nameRu = names["ru"].asText(),
                    nameUz = names["uz"].asText(),
                    nameEn = names["en"].asText(),
                )
            )
        }

        var chipSort = 0
        for (diseaseNode in root["diseases"]) {
            val names = diseaseNode["names"]
            val icdCodes = diseaseNode["icd_codes"].map { it.asText() }
            val diseaseId = diseaseNode["disease_id"].asText()
            diseases.save(
                ClinicalDisease(
                    diseaseId = diseaseId,
                    groupId = diseaseNode["group_id"].asText(),
                    numberVal = diseaseNode["number"].asInt(),
                    slug = diseaseNode["slug"].asText(),
                    icdCodes = icdCodes,
                    nameRu = names["ru"].asText(),
                    nameUz = names["uz"].asText(),
                    nameEn = names["en"].asText(),
                )
            )

            var sort = 0
            for (chipNode in diseaseNode["chips"]) {
                val chipId = chipNode["chip_id"].asText()
                val field = chipNode["field"].asText()
                val variables = chipNode["variables"]?.map { it.asText() } ?: emptyList()
                val labels = chipNode["labels"]
                chips.save(
                    ClinicalChip(
                        chipId = chipId,
                        fieldName = field,
                        variables = variables,
                        priority = chipNode["priority"]?.asInt() ?: 50,
                    )
                )
                for (locale in listOf("ru", "uz", "en")) {
                    val label = labels[locale].asText()
                    chipI18n.save(
                        ClinicalChipI18n(
                            id = ClinicalChipI18nId(chipId, locale),
                            label = label,
                        )
                    )
                    synthesisTemplates.save(
                        ClinicalChipSynthesisTemplate(
                            chipId = chipId,
                            locale = locale,
                            fieldName = field,
                            sentenceTemplate = ClinicalSynthesisTemplateBuilder.build(field, label, locale, variables),
                            sortOrder = chipSort++,
                        )
                    )
                }
                diseaseChips.save(
                    ClinicalDiseaseChip(
                        id = ClinicalDiseaseChipId(diseaseId, chipId),
                        sortOrder = sort++,
                    )
                )
            }
        }
    }

    private fun seedOcclusionLibrary() {
        val root = readJson("clinical-engine/occlusion_chip_library.json")
        for (chipNode in root["chips"]) {
            val labels = chipNode["labels"]
            occlusionChips.save(
                ClinicalOcclusionChip(
                    chipId = chipNode["chip_id"].asText(),
                    angleClass = chipNode["angle_class"]?.takeIf { !it.isNull }?.asText(),
                    icdHint = chipNode["icd_hint"]?.asText(),
                    variables = chipNode["variables"]?.map { it.asText() } ?: emptyList(),
                    priority = chipNode["priority"]?.asInt() ?: 50,
                    labelRu = labels["ru"].asText(),
                    labelUz = labels["uz"].asText(),
                    labelEn = labels["en"].asText(),
                )
            )
        }
    }

    private fun seedSharedLibraries() {
        val root = readJson("clinical-engine/shared_libraries.json")
        seedSharedSection(root["oral_mucosa_variants"], "mucosa")
        seedSharedSection(root["xray_templates"], "xray")
        seedSharedSection(root["prescription_categories"], "rx")
    }

    private fun seedSharedSection(section: JsonNode?, type: String) {
        if (section == null || !section.isArray) return
        for (node in section) {
            val id = node["variant_id"]?.asText()
                ?: node["template_id"]?.asText()
                ?: node["category_id"]?.asText()
                ?: continue
            val labels = node["labels"]
            sharedTemplates.save(
                ClinicalSharedTemplate(
                    templateId = id,
                    templateType = type,
                    fieldName = node["field"].asText(),
                    priority = node["priority"]?.asInt() ?: 50,
                    correlates = node["correlates"]?.map { it.asText() } ?: emptyList(),
                    labelRu = labels["ru"].asText(),
                    labelUz = labels["uz"].asText(),
                    labelEn = labels["en"].asText(),
                )
            )
        }
    }

    private fun seedToothKeys() {
        val permanent = listOf(
            "18", "17", "16", "15", "14", "13", "12", "11",
            "21", "22", "23", "24", "25", "26", "27", "28",
            "48", "47", "46", "45", "44", "43", "42", "41",
            "31", "32", "33", "34", "35", "36", "37", "38",
        )
        val primary = listOf(
            "55", "54", "53", "52", "51",
            "61", "62", "63", "64", "65",
            "71", "72", "73", "74", "75",
            "85", "84", "83", "82", "81",
        )
        var sort = 0
        for (key in permanent) {
            toothKeys.save(
                ClinicalDentalToothKey(
                    fdiKey = key,
                    dentition = "permanent",
                    quadrant = quadrantFor(key),
                    sortOrder = sort++,
                )
            )
        }
        for (key in primary) {
            toothKeys.save(
                ClinicalDentalToothKey(
                    fdiKey = key,
                    dentition = "primary",
                    quadrant = quadrantFor(key),
                    sortOrder = sort++,
                )
            )
        }
    }

    private fun quadrantFor(fdi: String): String {
        val first = fdi.firstOrNull()?.digitToIntOrNull() ?: return "unknown"
        return when (first) {
            1, 5 -> "upper_right"
            2, 6 -> "upper_left"
            3, 7 -> "lower_left"
            4, 8 -> "lower_right"
            else -> "unknown"
        }
    }

    private fun readJson(path: String): JsonNode =
        objectMapper.readTree(ClassPathResource(path).inputStream)
}
