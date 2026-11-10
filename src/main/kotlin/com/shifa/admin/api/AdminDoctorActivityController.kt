package com.shifa.admin.api

import com.shifa.admin.api.dto.DoctorSubscriptionBillingRequest
import com.shifa.admin.api.dto.SmsRemindersAllowedRequest
import com.shifa.admin.application.AdminDoctorActivityService
import com.shifa.admin.application.AuditService
import com.shifa.doctors.api.EarlyPartnerContractIssueDto
import com.shifa.doctors.application.EarlyPartnerContractService
import com.shifa.notifications.application.DoctorSmsBillingService
import com.shifa.shared.security.AdminPrincipal
import jakarta.servlet.http.HttpServletRequest
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
    private val auditService: AuditService,
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

    /** Update admin-managed trial length and/or monthly charge for a doctor. */
    @PatchMapping("/{doctorId}/subscription-billing")
    fun updateSubscriptionBilling(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable doctorId: Long,
        @RequestBody body: DoctorSubscriptionBillingRequest,
        httpRequest: HttpServletRequest,
    ): DoctorActivityRowDto {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot change subscription billing")
        }
        return auditService.audited(
            adminUser = principal.adminProfile.user,
            actionType = "DOCTOR_SUBSCRIPTION_BILLING_CHANGED",
            entityType = "DOCTOR_PROFILE",
            entityId = doctorId,
            request = httpRequest,
            // What was sent (changed) and the terms in force afterwards.
            details = { row ->
                mapOf(
                    "changed" to listOfNotNull(
                        body.trialPeriodMonths?.let { "trialPeriodMonths" },
                        body.monthlyChargeUsd?.let { "monthlyChargeUsd" },
                    ),
                    "trialPeriodMonths" to row.trialPeriodMonths,
                    "monthlyChargeUsd" to row.monthlyChargeUsd,
                )
            },
        ) {
            adminDoctorActivityService.updateSubscriptionBilling(
                doctorId = doctorId,
                trialPeriodMonths = body.trialPeriodMonths,
                monthlyChargeUsd = body.monthlyChargeUsd,
            )
        }
    }

    /** Grant or revoke permission for a doctor to enable patient SMS appointment reminders. */
    @PatchMapping("/{doctorId}/sms-reminders-allowed")
    fun setSmsRemindersAllowed(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable doctorId: Long,
        @RequestBody body: SmsRemindersAllowedRequest,
        httpRequest: HttpServletRequest,
    ): Map<String, Any> {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot change SMS settings")
        }
        val doctor = auditService.audited(
            adminUser = principal.adminProfile.user,
            actionType = "DOCTOR_SMS_REMINDERS_ALLOWED_CHANGED",
            entityType = "DOCTOR_PROFILE",
            entityId = doctorId,
            request = httpRequest,
            details = { mapOf("allowed" to it.smsRemindersAllowed) },
        ) { doctorSmsBillingService.setSmsRemindersAllowed(doctorId, body.allowed) }
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
        httpRequest: HttpServletRequest,
    ): EarlyPartnerContractIssueDto {
        if (principal.isReadOnly()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Read-only admins cannot issue contracts")
        }
        return auditService.audited(
            adminUser = principal.adminProfile.user,
            actionType = "EARLY_PARTNER_CONTRACT_ISSUED",
            entityType = "DOCTOR_PROFILE",
            entityId = doctorId,
            request = httpRequest,
            details = { mapOf("contractNumber" to it.contractNumber, "newAllocation" to it.newAllocation) },
        ) { earlyPartnerContractService.issueForDoctor(doctorId) }
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
