package com.shifa.clinicalengine.service

import com.shifa.clinicalengine.domain.ClinicalOcclusionChip
import com.shifa.clinicalengine.domain.ClinicalSharedTemplate
import com.shifa.clinicalengine.repo.ClinicalGroupRepository
import com.shifa.clinicalengine.repo.ClinicalOcclusionChipRepository
import com.shifa.clinicalengine.repo.ClinicalSharedTemplateRepository
import com.shifa.clinicalengine.web.dto.ClinicalGroupDto
import com.shifa.clinicalengine.web.dto.ClinicalLocalizedNameDto
import com.shifa.clinicalengine.web.dto.ClinicalOcclusionChipDto
import com.shifa.clinicalengine.web.dto.ClinicalSharedTemplateDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory cache for static Clinical Engine catalog slices.
 * Avoids repeated DB reads on hot read paths (groups, occlusion, shared templates).
 */
@Component
class ClinicalEngineCatalogCache(
    private val groups: ClinicalGroupRepository,
    private val occlusionChips: ClinicalOcclusionChipRepository,
    private val sharedTemplates: ClinicalSharedTemplateRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val groupsCache = AtomicReference<List<ClinicalGroupDto>?>(null)
    private val occlusionCache = AtomicReference<List<ClinicalOcclusionChipDto>?>(null)
    private val sharedByType = AtomicReference<Map<String, List<ClinicalSharedTemplateDto>>?>(null)
    private val sharedEntities = AtomicReference<Map<String, ClinicalSharedTemplate>?>(null)

    fun invalidate() {
        groupsCache.set(null)
        occlusionCache.set(null)
        sharedByType.set(null)
        sharedEntities.set(null)
        log.info("Clinical Engine catalog cache invalidated")
    }

    fun warm() {
        listGroups()
        listOcclusionChips()
        listSharedTemplates("mucosa")
        listSharedTemplates("xray")
        listSharedTemplates("rx")
        log.info("Clinical Engine catalog cache warmed")
    }

    fun listGroups(): List<ClinicalGroupDto> {
        groupsCache.get()?.let { return it }
        synchronized(this) {
            groupsCache.get()?.let { return it }
            val loaded = groups.findAllByOrderBySortOrderAsc().map {
                ClinicalGroupDto(
                    groupId = it.groupId,
                    sortOrder = it.sortOrder,
                    names = ClinicalLocalizedNameDto(it.nameRu, it.nameUz, it.nameEn),
                )
            }
            groupsCache.set(loaded)
            return loaded
        }
    }

    fun listOcclusionChips(): List<ClinicalOcclusionChipDto> {
        occlusionCache.get()?.let { return it }
        synchronized(this) {
            occlusionCache.get()?.let { return it }
            val loaded = occlusionChips.findAllByOrderByPriorityAsc().map { toOcclusionDto(it) }
            occlusionCache.set(loaded)
            return loaded
        }
    }

    fun listSharedTemplates(type: String): List<ClinicalSharedTemplateDto> {
        val map = sharedByType.get()
        if (map != null && map.containsKey(type)) return map[type].orEmpty()
        synchronized(this) {
            val current = sharedByType.get()
            if (current != null && current.containsKey(type)) return current[type].orEmpty()
            val all = sharedTemplates.findAll()
            val dtoMap = all.groupBy { it.templateType }.mapValues { (_, rows) ->
                rows.sortedBy { it.priority }.map { toSharedDto(it) }
            }
            val entityMap = all.associateBy { sharedChipId(it.templateId) }
            sharedByType.set(dtoMap)
            sharedEntities.set(entityMap)
            return dtoMap[type].orEmpty()
        }
    }

    fun sharedTemplate(chipId: String): ClinicalSharedTemplate? {
        listSharedTemplates("mucosa")
        listSharedTemplates("xray")
        listSharedTemplates("rx")
        return sharedEntities.get()?.get(chipId)
    }

    companion object {
        const val SHARED_PREFIX = "shared."

        fun sharedChipId(templateId: String): String = "$SHARED_PREFIX$templateId"

        fun isSharedChipId(chipId: String): Boolean = chipId.startsWith(SHARED_PREFIX)

        fun templateIdFromSharedChipId(chipId: String): String =
            chipId.removePrefix(SHARED_PREFIX)
    }

    private fun toOcclusionDto(it: ClinicalOcclusionChip) = ClinicalOcclusionChipDto(
        chipId = it.chipId,
        angleClass = it.angleClass,
        icdHint = it.icdHint,
        labels = ClinicalLocalizedNameDto(it.labelRu, it.labelUz, it.labelEn),
        variables = it.variables,
        priority = it.priority,
    )

    private fun toSharedDto(it: ClinicalSharedTemplate) = ClinicalSharedTemplateDto(
        templateId = it.templateId,
        type = it.templateType,
        field = it.fieldName,
        labels = ClinicalLocalizedNameDto(it.labelRu, it.labelUz, it.labelEn),
        priority = it.priority,
        correlates = it.correlates,
    )
}
