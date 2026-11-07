package com.shifa.clinics.api

import com.shifa.admin.application.AdminClinicService
import com.shifa.clinics.api.dto.UpdateFinanceSettingsBody
import com.shifa.clinics.api.dto.UpdateMemberRevenueShareBody
import com.shifa.clinics.api.dto.VisitChargeLineBody
import com.shifa.clinics.application.ClinicWorkspaceService
import com.shifa.clinics.application.VisitChargeService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
class ClinicWorkspaceController(
    private val workspace: ClinicWorkspaceService,
    private val visitChargeService: VisitChargeService,
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

    @PatchMapping("/api/clinics/{clinicId}/finance-settings")
    fun updateFinanceSettings(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestBody body: UpdateFinanceSettingsBody,
    ): ClinicWorkspaceService.ClinicFinanceSettingsDto =
        workspace.updateFinanceSettings(principal, clinicId, body.defaultDoctorRevenueSharePercent)

    @PatchMapping("/api/clinics/{clinicId}/members/{doctorProfileId}/revenue-share")
    fun updateMemberRevenueShare(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable doctorProfileId: Long,
        @RequestBody body: UpdateMemberRevenueShareBody,
    ): ClinicWorkspaceService.MemberRevenueShareDto =
        workspace.updateMemberRevenueShare(
            principal,
            clinicId,
            doctorProfileId,
            body.doctorRevenueSharePercent,
        )

    @GetMapping("/api/clinics/{clinicId}/patients")
    fun patients(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @RequestParam(required = false) q: String?,
        pageable: Pageable,
    ): Page<ClinicWorkspaceService.ClinicPatientRow> =
        workspace.listPatients(principal, clinicId, q, pageable)

    @GetMapping("/api/clinics/{clinicId}/patients/{patientId}/appointments")
    fun patientAppointments(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable patientId: Long,
    ): List<ClinicWorkspaceService.ClinicPatientAppointmentRow> =
        workspace.listPatientAppointments(principal, clinicId, patientId)

    @PostMapping("/api/clinics/{clinicId}/appointments/{appointmentId}/visit-charges")
    @io.swagger.v3.oas.annotations.Parameter(
        name = "Idempotency-Key",
        `in` = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
        required = false,
        description = "Optional, 8-128 of [A-Za-z0-9_-] (a UUID). The first 2xx answer for the key is replayed for 24 h " +
            "(header Idempotent-Replayed: true); another path or body with the same key is 409 IDEMPOTENCY_KEY_REUSED, " +
            "a key still running is 409 IDEMPOTENCY_IN_PROGRESS, a malformed key is 400 IDEMPOTENCY_KEY_INVALID.",
    )
    fun addVisitCharges(
        @AuthenticationPrincipal principal: Any,
        @PathVariable clinicId: Long,
        @PathVariable appointmentId: Long,
        @RequestBody lines: List<VisitChargeLineBody>,
    ): Map<String, Long> {
        val plan = visitChargeService.addVisitCharges(
            principal,
            clinicId,
            appointmentId,
            lines.map { VisitChargeService.VisitChargeLine(it.catalogItemId, it.quantity, it.unitPriceMinor) },
        )
        return mapOf("treatmentPlanId" to plan.id)
    }
}
