package com.shifa.web

import com.shifa.security.AdminPrincipal
import com.shifa.service.AdminDoctorActivityService
import com.shifa.service.EarlyPartnerContractService
import com.shifa.web.dto.DoctorActivityDetailDto
import com.shifa.web.dto.DoctorActivityRowDto
import com.shifa.web.dto.EarlyPartnerContractIssueDto
import org.springframework.data.domain.Page
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/admin/doctors")
class AdminDoctorActivityController(
    private val adminDoctorActivityService: AdminDoctorActivityService,
    private val earlyPartnerContractService: EarlyPartnerContractService,
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

    /** Allocate or refresh early-partner contract data for PDF generation (sequential number per new doctor). */
    @Suppress("UNUSED_PARAMETER")
    @PostMapping("/{doctorId}/early-partner-contract/issue")
    fun issueEarlyPartnerContract(
        @AuthenticationPrincipal principal: AdminPrincipal,
        @PathVariable doctorId: Long,
    ): EarlyPartnerContractIssueDto {
        return earlyPartnerContractService.issueForDoctor(doctorId)
    }
}
