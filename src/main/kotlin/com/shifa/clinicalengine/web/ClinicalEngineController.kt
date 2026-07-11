package com.shifa.clinicalengine.web

import com.shifa.clinicalengine.service.ClinicalEngineService
import com.shifa.clinicalengine.service.ClinicalSmartPriorityService
import com.shifa.clinicalengine.service.ClinicalSynthesisService
import com.shifa.clinicalengine.web.dto.*
import com.shifa.security.DoctorPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/clinical-engine")
class ClinicalEngineController(
    private val engine: ClinicalEngineService,
    private val synthesis: ClinicalSynthesisService,
    private val smartPriority: ClinicalSmartPriorityService,
) {

    @GetMapping("/groups")
    fun listGroups(
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<List<ClinicalGroupDto>> =
        ResponseEntity.ok(engine.listGroups())

    @GetMapping("/groups/{groupId}/diseases")
    fun listDiseases(
        @PathVariable groupId: String,
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<List<ClinicalDiseaseSummaryDto>> =
        ResponseEntity.ok(engine.listDiseasesByGroup(groupId))

    @GetMapping("/diseases/{diseaseId}")
    fun getDisease(
        @PathVariable diseaseId: String,
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<ClinicalDiseaseDetailDto> =
        ResponseEntity.ok(engine.getDisease(diseaseId))

    @GetMapping("/doctors/me/top-diagnoses")
    fun topDiagnoses(
        @RequestParam(defaultValue = "5") limit: Int,
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<List<ClinicalTopDiagnosisDto>> =
        ResponseEntity.ok(smartPriority.topDiagnoses(principal.profile.id!!, limit))

    @GetMapping("/chips/search")
    fun searchChips(
        @RequestParam q: String,
        @RequestParam(defaultValue = "ru") locale: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<List<ClinicalChipDto>> =
        ResponseEntity.ok(engine.searchChips(q, locale, limit))

    @GetMapping("/occlusion/chips")
    fun occlusionChips(
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<List<ClinicalOcclusionChipDto>> =
        ResponseEntity.ok(engine.listOcclusionChips())

    @GetMapping("/shared/{type}")
    fun sharedTemplates(
        @PathVariable type: String,
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<List<ClinicalSharedTemplateDto>> =
        ResponseEntity.ok(engine.listSharedTemplates(type))

    @PostMapping("/synthesize")
    fun synthesize(
        @RequestBody request: ClinicalSynthesizeRequestDto,
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<ClinicalSynthesizeResponseDto> =
        ResponseEntity.ok(synthesis.synthesize(request.locale, request.selections))

    @PostMapping("/record-usage")
    fun recordUsage(
        @RequestBody request: ClinicalRecordUsageRequestDto,
        @AuthenticationPrincipal principal: DoctorPrincipal,
    ): ResponseEntity<Void> {
        smartPriority.recordUsage(principal.profile.id!!, request.diseaseId)
        return ResponseEntity.noContent().build()
    }
}
