package com.shifa.doctors.application

import com.shifa.auth.domain.User
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.doctors.domain.EarlyPartnerContract
import com.shifa.doctors.infrastructure.EarlyPartnerContractRepository
import com.shifa.shared.CodedStatusException
import com.shifa.shared.ErrorCode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Optional
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** BE-16: the doctor's contract summary and the ported admin PDF. */
class EarlyPartnerContractTest {
    private val repo = mock(EarlyPartnerContractRepository::class.java)
    private val service = EarlyPartnerContractService(
        mock(com.shifa.doctors.infrastructure.DoctorProfileRepository::class.java),
        repo,
        mock(com.shifa.admin.application.SystemConfigs::class.java),
    )

    private fun contract(first: String = "Shohruhmirzo", last: String = "Saidov") = EarlyPartnerContract(
        doctorProfile = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = first, lastName = last),
        contractSeq = 461,
        contractNumber = "SHIFA-0461",
        effectiveDate = LocalDate.parse("2026-06-01"),
        termMonths = 6,
        partnerFullName = "$first $last",
        partnerClinic = "Shifa Demo Clinic",
        partnerPhone = "+998901234567",
        createdAt = OffsetDateTime.parse("2026-05-25T10:00:00Z"),
    )

    @Test
    fun `the summary ends term months after the start and expires after the end date`() {
        `when`(repo.findByDoctorProfileId(9L)).thenReturn(Optional.of(contract()))

        val active = service.summaryForDoctor(9L, LocalDate.parse("2026-12-01"))
        assertEquals(LocalDate.parse("2026-12-01"), active.endDate)
        assertTrue(active.active)
        assertFalse(service.summaryForDoctor(9L, LocalDate.parse("2026-12-02")).active)
    }

    @Test
    fun `no contract is 404 NO_CONTRACT`() {
        `when`(repo.findByDoctorProfileId(9L)).thenReturn(Optional.empty())

        val e = assertThrows(CodedStatusException::class.java) { service.summaryForDoctor(9L) }
        assertEquals(404, e.statusCode.value())
        assertEquals(ErrorCode.NO_CONTRACT, e.code)
        assertThrows(CodedStatusException::class.java) { service.pdfForDoctor(9L) }
    }

    @Test
    fun `the pdf is a valid document with the number and the terms on page 1`() {
        `when`(repo.findByDoctorProfileId(9L)).thenReturn(Optional.of(contract()))

        val pdf = service.pdfForDoctor(9L)
        assertEquals("SHIFA-0461", pdf.contractNumber)
        assertEquals("%PDF", String(pdf.bytes.copyOfRange(0, 4)))
        Loader.loadPDF(pdf.bytes).use { doc ->
            assertTrue(doc.numberOfPages >= 2, "pages: ${doc.numberOfPages}")
            val first = PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(doc)
            listOf("SHIFA-0461", "SHIFA ERTA FOYDALANUVCHI HAMKORLIK", "01.06.2026", "6 (olti) oy", "Sahifa 1 / ${doc.numberOfPages}")
                .forEach { assertTrue(first.contains(it), "missing \"$it\" on page 1:\n$first") }
            val all = PDFTextStripper().getText(doc)
            listOf("IMZOLAR", "Bekzodbek Qobilov", "Shohruhmirzo Saidov", "Shohruhmirzo S.", "25 May 2026", "Roli: [X] Shifokor", "+998901234567")
                .forEach { assertTrue(all.contains(it), "missing \"$it\":\n$all") }
        }
    }

    @Test
    fun `a Cyrillic name still renders, in the regular face`() {
        `when`(repo.findByDoctorProfileId(9L)).thenReturn(Optional.of(contract("Азиза", "Каримова")))

        val text = Loader.loadPDF(service.pdfForDoctor(9L).bytes).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Азиза К."), text)
    }

    @Test
    fun `the signature ink abbreviates the last name`() {
        assertEquals("Ada L.", EarlyPartnerContractPdf.signatureInk("Ada", "lovelace", null))
        assertEquals("Ada L.", EarlyPartnerContractPdf.signatureInk(null, null, "Ada Byron Lovelace"))
        assertEquals("Ada", EarlyPartnerContractPdf.signatureInk(" ", null, "Ada"))
        assertEquals("", EarlyPartnerContractPdf.signatureInk(null, null, null))
    }
}
