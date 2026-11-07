package com.shifa.doctors.api

import com.shifa.doctors.api.dto.DoctorContractDto
import com.shifa.doctors.application.EarlyPartnerContractService
import com.shifa.shared.security.DoctorPrincipal
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The signed-in doctor's early-partner contract; 404 `NO_CONTRACT` when the admin never issued one. */
@RestController
@RequestMapping("/api/doctors/me/contract")
class DoctorContractController(private val contracts: EarlyPartnerContractService) {

    @GetMapping
    fun myContract(@AuthenticationPrincipal principal: DoctorPrincipal): DoctorContractDto {
        val c = contracts.summaryForDoctor(principal.profile.id)
        return DoctorContractDto(
            contractNumber = c.contractNumber,
            effectiveDate = c.effectiveDate.toString(),
            endDate = c.endDate.toString(),
            termMonths = c.termMonths,
            issuedAt = c.issuedAt.toInstant().toString(),
            status = if (c.active) "ACTIVE" else "EXPIRED",
        )
    }

    /** The contract as the admin panel prints it (Uzbek), shown inline. */
    @GetMapping("/pdf")
    fun myContractPdf(@AuthenticationPrincipal principal: DoctorPrincipal): ResponseEntity<ByteArray> {
        val file = contracts.pdfForDoctor(principal.profile.id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("${file.contractNumber}.pdf").build().toString())
            .body(file.bytes)
    }
}
