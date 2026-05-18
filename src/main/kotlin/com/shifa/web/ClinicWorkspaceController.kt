package com.shifa.web

import com.shifa.service.AdminClinicService
import com.shifa.service.ClinicWorkspaceService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ClinicWorkspaceController(
    private val workspace: ClinicWorkspaceService,
) {

    @GetMapping("/api/me/clinics")
    fun myClinics(@AuthenticationPrincipal principal: Any): List<ClinicWorkspaceService.MyClinicSummary> =
        workspace.listMyClinics(principal)

    @GetMapping("/api/clinics/{clinicId}/overview-stats")
    fun overviewStats(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
    ): ClinicWorkspaceService.OverviewStats =
        workspace.getOverviewStats(principal, clinicId)

    @GetMapping("/api/clinics/{clinicId}/members")
    fun members(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
    ): List<AdminClinicService.ClinicDoctorDto> =
        workspace.listMembers(principal, clinicId)

    @GetMapping("/api/clinics/{clinicId}/patients")
    fun patients(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam(required = false) q: String?,
        pageable: Pageable,
    ): Page<ClinicWorkspaceService.ClinicPatientRow> =
        workspace.listPatients(principal, clinicId, q, pageable)
}
