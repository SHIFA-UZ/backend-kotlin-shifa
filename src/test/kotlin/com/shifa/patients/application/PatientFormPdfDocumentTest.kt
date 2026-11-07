package com.shifa.patients.application

import com.shifa.auth.domain.User
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.documents.application.FiledDocument
import com.shifa.documents.application.PatientDocumentService
import com.shifa.documents.application.PatientDocumentStorageService
import com.shifa.documents.domain.PatientDocument
import com.shifa.documents.domain.PatientDocumentCategory
import com.shifa.patients.domain.PatientForm
import com.shifa.patients.domain.PatientProfile
import com.shifa.patients.infrastructure.PatientFormRepository
import java.time.LocalDate
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException

/** Filing the server-rendered 025-2 (BE-01): a new linked FORM_025_2 document once, then the same one re-rendered. */
class PatientFormPdfDocumentTest {

    private val forms = mock(PatientFormRepository::class.java)
    private val docs = mock(PatientDocumentService::class.java)
    private val storage = mock(PatientDocumentStorageService::class.java)
    private val service = PatientFormService(
        mock(com.shifa.patients.infrastructure.PatientProfileRepository::class.java),
        forms,
        docs,
        mock(com.shifa.notifications.application.NotificationService::class.java),
        mock(com.shifa.ai.application.ClinicalRagIndexingService::class.java),
        mock(com.shifa.ai.clinicalengine.application.ClinicalSmartPriorityService::class.java),
        mock(com.shifa.ai.clinicalengine.application.DentalChartKeyValidator::class.java),
        storage,
    )
    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "A", lastName = "B")
    private val patient = PatientProfile(id = 77L, fullName = "Иванов Иван")

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(type: Class<T>): T = ArgumentMatchers.any(type) ?: (null as T)

    private fun form(document: PatientDocument? = null) = PatientForm(
        id = 5L, patient = patient, templateId = "025-2", date = LocalDate.of(2026, 11, 6), fullName = "Иванов Иван",
        formNumber = 12, document = document,
    ).also { `when`(forms.findById(5L)).thenReturn(Optional.of(it)) }

    private fun stubStorage(path: String) {
        `when`(storage.saveBytes(ArgumentMatchers.anyLong(), anyOf(ByteArray::class.java), ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
            .thenReturn(PatientDocumentStorageService.SaveResult(path, "https://x/$path"))
        `when`(docs.save(anyOf(PatientDocument::class.java))).thenAnswer { (it.arguments[0] as PatientDocument).apply { id = id ?: 55L } }
        `when`(storage.filed(anyOf(PatientDocument::class.java))).thenAnswer {
            val d = it.arguments[0] as PatientDocument
            FiledDocument(d.id ?: 0, "https://x/${d.filePath}")
        }
    }

    @Test
    fun `the first filing makes a doctor-private FORM_025_2 document and links it`() {
        val form = form()
        stubStorage("patientdocuments/77/new.pdf")

        val filed = service.filePdfDocument(77L, 5L, doctor)

        assertEquals(FiledDocument(55L, "https://x/patientdocuments/77/new.pdf"), filed)
        val linked = requireNotNull(form.document)
        assertEquals(PatientDocumentCategory.FORM_025_2.code, linked.category)
        assertEquals("Form 025-2 (2026-11-06)", linked.title)
        assertFalse(linked.isSharedWithTeam)
        assertSame(doctor, linked.uploadedByDoctor)
        verify(forms).save(form)
    }

    @Test
    fun `filing again replaces the linked document's file`() {
        val existing = PatientDocument(id = 60L, patient = patient, title = "Form 025-2 (2026-11-06)", filePath = "patientdocuments/77/old.pdf")
        val form = form(existing)
        stubStorage("patientdocuments/77/new.pdf")

        val filed = service.filePdfDocument(77L, 5L, doctor)

        assertEquals(FiledDocument(60L, "https://x/patientdocuments/77/new.pdf"), filed)
        assertSame(existing, form.document)
        assertEquals(PatientDocumentCategory.FORM_025_2.code, existing.category)
        verify(storage).deleteFile("patientdocuments/77/old.pdf")
        verify(forms, never()).save(anyOf(PatientForm::class.java))
    }

    @Test
    fun `a form of another patient is refused before anything is written`() {
        form()
        assertThrows<IllegalArgumentException> { service.filePdfDocument(78L, 5L, doctor) }
        verify(docs, never()).save(anyOf(PatientDocument::class.java))
    }

    @Test
    fun `an unknown form is not found`() {
        `when`(forms.findById(6L)).thenReturn(Optional.empty())
        val e = assertThrows<ResponseStatusException> { service.renderPdf(77L, 6L) }
        assertEquals(404, e.statusCode.value())
    }

    @Test
    fun `the read-only render is named after the form number`() {
        form()
        val pdf = service.renderPdf(77L, 5L)
        assertEquals("025-2-12.pdf", pdf.fileName)
        assertEquals("%PDF", String(pdf.bytes.copyOfRange(0, 4)))
    }
}
