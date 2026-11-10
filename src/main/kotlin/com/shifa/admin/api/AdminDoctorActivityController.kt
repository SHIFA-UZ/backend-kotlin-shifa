package com.shifa.admin.api

import com.shifa.admin.api.dto.DoctorSubscriptionBillingRequest
import com.shifa.admin.api.dto.SmsRemindersAllowedRequest
import com.shifa.admin.application.AdminDoctorActivityService
import com.shifa.doctors.api.EarlyPartnerContractIssueDto
import com.shifa.doctors.application.EarlyPartnerContractService
import com.shifa.notifications.application.DoctorSmsBillingService
import com.shifa.shared.security.AdminPrincipal
import java.time.LocalDate
import org.springframework.data.domain.Page
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException


@RestController
@RequestMapping("/api/admin/doctors")
class AdminDoctorActivityController(
    private val adminDoctorActivityService: AdminDoctorActivityService,
    private val earlyPartnerContractService: EarlyPartnerContractService,
    private val doctorSmsBillingService: DoctorSmsBillingService,
) {

    /** List doctor activity aggregates (pagination + sorting in memory after filter). Read-only admins allowed. */
    @Suppress("UNUSED_PARAMETER")
    @GetMapping("/activity")
    fun listActivity(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "appointments") sort: String,
        @RequestParam(defaultValue = "desc") dir: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): Page<DoctorActivityRowDto> {
        return adminDoctorActivityService.listPaged(
            from = from,
            toInclusive = to,
            searchTrimmed = search?.trim(),
            sortOneOf = sort,
            dirDescending = dir.equals("desc", ignoreCase = true),
            pageZeroBased = page,
            pageSize = size,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    @GetMapping("/{doctorId}/activity")
    fun doctorActivity(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable doctorId: Long,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): DoctorActivityDetailDto {
        return adminDoctorActivityService.doctorDetail(
            doctorId = doctorId,
            from = from,
            toInclusive = to,
        )
    }

    /**
     * Update admin-managed trial length and/or monthly charge for a doctor. The answer's counters are all time, or
     * cover `from`..`to` (both or neither, as on the list) when the dates are sent.
     */
    @PatchMapping("/{doctorId}/subscription-billing")
    fun updateSubscriptionBilling(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable doctorId: Long,
        @RequestBody body: DoctorSubscriptionBillingRequest,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): DoctorActivityRowDto {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot change subscription billing")
        }
        // Checked before anything is saved: one date of two, or dates the wrong way round, is a 400.
        val windowed = adminDoctorActivityService.parseOptionalWindow(from, to) != null
        val allTime = adminDoctorActivityService.updateSubscriptionBilling(
            doctorId = doctorId,
            trialPeriodMonths = body.trialPeriodMonths,
            monthlyChargeUsd = body.monthlyChargeUsd,
        )
        // With dates the answer is the row of that window, as the list shows it; without, the all-time row as before.
        return if (windowed) adminDoctorActivityService.doctorDetail(doctorId, from, to).row else allTime
    }

    /** Grant or revoke permission for a doctor to enable patient SMS appointment reminders. */
    @PatchMapping("/{doctorId}/sms-reminders-allowed")
    fun setSmsRemindersAllowed(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable doctorId: Long,
        @RequestBody body: SmsRemindersAllowedRequest,
    ): Map<String, Any> {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot change SMS settings")
        }
        val doctor = doctorSmsBillingService.setSmsRemindersAllowed(doctorId, body.allowed)
        return mapOf(
            "doctorId" to doctor.id,
            "smsRemindersAllowed" to doctor.smsRemindersAllowed,
        )
    }

    /** Allocate or refresh early-partner contract data for PDF generation (sequential number per new doctor). */
    @PostMapping("/{doctorId}/early-partner-contract/issue")
    fun issueEarlyPartnerContract(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable doctorId: Long,
    ): EarlyPartnerContractIssueDto {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot issue contracts")
        }
        return earlyPartnerContractService.issueForDoctor(doctorId)
    }

    /** The issued contract as a PDF (the same file the doctor's app shows); 404 `NO_CONTRACT` when none was issued. */
    @Suppress("UNUSED_PARAMETER")
    @GetMapping("/{doctorId}/early-partner-contract/pdf")
    fun earlyPartnerContractPdf(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable doctorId: Long,
    ): org.springframework.http.ResponseEntity<ByteArray> {
        val file = earlyPartnerContractService.pdfForDoctor(doctorId)
        return org.springframework.http.ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
            .header(
                org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment().filename("${file.contractNumber}.pdf").build().toString(),
            )
            .body(file.bytes)
    }
}
