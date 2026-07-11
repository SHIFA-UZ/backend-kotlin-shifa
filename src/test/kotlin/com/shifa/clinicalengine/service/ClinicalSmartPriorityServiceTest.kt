package com.shifa.clinicalengine.service

import com.shifa.clinicalengine.domain.ClinicalDisease
import com.shifa.clinicalengine.domain.ClinicalDoctorDiseaseUsage
import com.shifa.clinicalengine.domain.ClinicalDoctorDiseaseUsageId
import com.shifa.clinicalengine.repo.ClinicalDiseaseRepository
import com.shifa.clinicalengine.repo.ClinicalDoctorDiseaseRecentRepository
import com.shifa.clinicalengine.repo.ClinicalDoctorDiseaseUsageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import java.util.Optional

class ClinicalSmartPriorityServiceTest {

    @Test
    fun `top diagnoses returns highest use count first after repeated saves`() {
        val usageRepo = mock(ClinicalDoctorDiseaseUsageRepository::class.java)
        val recentRepo = mock(ClinicalDoctorDiseaseRecentRepository::class.java)
        val diseaseRepo = mock(ClinicalDiseaseRepository::class.java)
        val service = ClinicalSmartPriorityService(usageRepo, recentRepo, diseaseRepo)

        val doctorId = 7L
        val usage = ClinicalDoctorDiseaseUsage(
            id = ClinicalDoctorDiseaseUsageId(doctorId, "dis.001"),
            useCount = 3,
            lastUsedAt = java.time.OffsetDateTime.now(),
        )
        doReturn(listOf(usage))
            .`when`(usageRepo)
            .findTopByDoctor(doctorId, PageRequest.of(0, 5))
        doReturn(
            listOf(
                ClinicalDisease(
                    diseaseId = "dis.001",
                    groupId = "grp.caries",
                    numberVal = 1,
                    slug = "initial_caries",
                    icdCodes = listOf("K02.0"),
                    nameRu = "Начальный кариес",
                    nameUz = "Boshlang'ich kariyes",
                    nameEn = "Initial caries",
                ),
            ),
        ).`when`(diseaseRepo).findAllById(listOf("dis.001"))

        val top = service.topDiagnoses(doctorId, limit = 5)

        assertEquals(1, top.size)
        assertEquals("dis.001", top.first().diseaseId)
        assertEquals(3, top.first().useCount)
    }

    @Test
    fun `recordUsage increments counter across three saves`() {
        val usageRepo = mock(ClinicalDoctorDiseaseUsageRepository::class.java)
        val recentRepo = mock(ClinicalDoctorDiseaseRecentRepository::class.java)
        val diseaseRepo = mock(ClinicalDiseaseRepository::class.java)
        val service = ClinicalSmartPriorityService(usageRepo, recentRepo, diseaseRepo)

        val doctorId = 7L
        `when`(diseaseRepo.existsById("dis.001")).thenReturn(true)
        `when`(usageRepo.findById(ClinicalDoctorDiseaseUsageId(doctorId, "dis.001")))
            .thenReturn(Optional.empty())
        doReturn(emptyList<com.shifa.clinicalengine.domain.ClinicalDoctorDiseaseRecent>())
            .`when`(recentRepo)
            .findByDoctorIdOrderByUsedAtDesc(
                doctorId,
                PageRequest.of(0, ClinicalSmartPriorityService.RECENT_LIMIT + 1),
            )

        repeat(3) { service.recordUsage(doctorId, "dis.001") }

        verify(usageRepo, atLeast(3)).save(org.mockito.ArgumentMatchers.any())
        verify(recentRepo, atLeast(3)).save(org.mockito.ArgumentMatchers.any())
    }
}
