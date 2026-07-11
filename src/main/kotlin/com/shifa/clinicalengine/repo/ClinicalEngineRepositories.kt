package com.shifa.clinicalengine.repo

import com.shifa.clinicalengine.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ClinicalGroupRepository : JpaRepository<ClinicalGroup, String> {
    fun findAllByOrderBySortOrderAsc(): List<ClinicalGroup>
}

interface ClinicalDiseaseRepository : JpaRepository<ClinicalDisease, String> {
    fun findByGroupIdAndActiveTrueOrderByNumberValAsc(groupId: String): List<ClinicalDisease>
    fun findByActiveTrueOrderByNumberValAsc(): List<ClinicalDisease>
}

interface ClinicalChipRepository : JpaRepository<ClinicalChip, String>

interface ClinicalChipI18nRepository : JpaRepository<ClinicalChipI18n, ClinicalChipI18nId> {
    fun findByIdChipIdIn(chipIds: Collection<String>): List<ClinicalChipI18n>

    @Query(
        """
        SELECT i FROM ClinicalChipI18n i
        WHERE i.id.locale = :locale
          AND LOWER(i.label) LIKE LOWER(CONCAT('%', :query, '%'))
        ORDER BY i.id.chipId
        """
    )
    fun searchByLocaleAndLabel(
        @Param("locale") locale: String,
        @Param("query") query: String,
        pageable: org.springframework.data.domain.Pageable,
    ): List<ClinicalChipI18n>
}

interface ClinicalChipSynthesisTemplateRepository : JpaRepository<ClinicalChipSynthesisTemplate, Long> {
    fun findByChipIdInAndLocale(chipIds: Collection<String>, locale: String): List<ClinicalChipSynthesisTemplate>
}

interface ClinicalDiseaseChipRepository : JpaRepository<ClinicalDiseaseChip, ClinicalDiseaseChipId> {
    fun findByIdDiseaseIdOrderBySortOrderAsc(diseaseId: String): List<ClinicalDiseaseChip>
}

interface ClinicalOcclusionChipRepository : JpaRepository<ClinicalOcclusionChip, String> {
    fun findAllByOrderByPriorityAsc(): List<ClinicalOcclusionChip>
}

interface ClinicalSharedTemplateRepository : JpaRepository<ClinicalSharedTemplate, String> {
    fun findByTemplateTypeOrderByPriorityAsc(templateType: String): List<ClinicalSharedTemplate>
}

interface ClinicalDentalToothKeyRepository : JpaRepository<ClinicalDentalToothKey, String>

interface ClinicalDoctorDiseaseUsageRepository : JpaRepository<ClinicalDoctorDiseaseUsage, ClinicalDoctorDiseaseUsageId> {
    @Query(
        """
        SELECT u FROM ClinicalDoctorDiseaseUsage u
        WHERE u.id.doctorId = :doctorId
        ORDER BY u.useCount DESC, u.lastUsedAt DESC
        """
    )
    fun findTopByDoctor(
        @Param("doctorId") doctorId: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): List<ClinicalDoctorDiseaseUsage>
}

interface ClinicalDoctorDiseaseRecentRepository : JpaRepository<ClinicalDoctorDiseaseRecent, Long> {
    fun findByDoctorIdOrderByUsedAtDesc(
        doctorId: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): List<ClinicalDoctorDiseaseRecent>
}
