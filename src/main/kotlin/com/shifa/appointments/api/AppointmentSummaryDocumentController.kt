package com.shifa.appointments.api

import com.shifa.appointments.application.AppointmentDocumentationService
import com.shifa.appointments.application.Appointments
import com.shifa.clinics.application.ClinicAccessService
import com.shifa.documents.application.FiledDocument
import com.shifa.documents.application.PatientDocumentStorageService
import com.shifa.shared.pdf.PdfLanguage
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** The visit summary PDF rendered on the server and filed as the patient's document (BE-01). */
@RestController
@RequestMapping("/api/appointments")
class AppointmentSummaryDocumentController(
    private val appts: Appointments,
    private val clinicAccess: ClinicAccessService,
    private val documentation: AppointmentDocumentationService,
    private val documentStorage: PatientDocumentStorageService,
) {

    /**
     * (Re)renders the summary as the patient's APPOINTMENT_NOTE document; idempotent. Only the visit's own doctor:
     * the summary is that doctor's document (colleagues and staff who can see the calendar get 403, so the signed
     * URL never reaches someone the document's own rules would refuse). 409 while the visit has no notes.
     */
    @PostMapping("/{appointmentId}/summary-document")
    fun summaryDocument(
        @AuthenticationPrincipal principal: Any,
        @PathVariable appointmentId: Long,
        @RequestParam(required = false) lang: String?,
    ): FiledDocument {
        val language = PdfLanguage.parse(lang)
        val appointment = appts.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId") }
        clinicAccess.assertCanAccessAppointmentResource(principal, appointment.doctor.id)
        if ((principal as? com.shifa.shared.security.DoctorPrincipal)?.profile?.id != appointment.doctor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the visit's doctor can file its summary")
        }
        val document = documentation.generateForAppointment(appointmentId, language)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "The visit has no notes yet, so there is no summary to file")
        return documentStorage.filed(document)
    }
}
