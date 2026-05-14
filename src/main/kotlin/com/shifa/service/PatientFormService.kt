package com.shifa.service

import com.shifa.domain.Notification
import com.shifa.domain.PatientForm
import com.shifa.domain.DoctorProfile
import com.shifa.domain.PatientDocumentCategory
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientDocumentRepository
import com.shifa.repo.PatientFormRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.web.dto.PatientFormDto
import com.shifa.web.dto.PatientFormFollowupDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PatientFormService(
    private val profiles: PatientProfileRepository,
    private val forms: PatientFormRepository,
    private val docs: PatientDocumentRepository,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService
) {

    fun list(patientId: Long): List<PatientFormDto> {
        val found = forms.findByPatientIdOrderByDateDesc(patientId)
        return found.map { toDto(it) }
    }

    fun getById(formId: Long): PatientFormDto {
        val form = forms.findById(formId)
            .orElseThrow { IllegalArgumentException("Form not found: $formId") }
        return toDto(form)
    }

    @Transactional
    fun create(
        patientId: Long,
        request: PatientFormDto,
        doctorName: String,
        doctorClinic: String?
    ): PatientFormDto {
        val patient = profiles.findById(patientId)
            .orElseThrow { IllegalArgumentException("Patient not found: $patientId") }

        val nextNumber = (forms.findMaxFormNumber(patientId, request.templateId) ?: 0) + 1

        val form = PatientForm(
            patient = patient,
            templateId = request.templateId,
            date = request.date,
            fullName = request.fullName,
            gender = request.gender,
            address = request.address,
            age = request.age,
            job = request.job,
            diagnosis = request.diagnosis,
            diagnosisCode = request.diagnosisCode,
            diagnosisDisplay = request.diagnosisDisplay,
            diagnosisSystem = request.diagnosisSystem,
            complaints = request.complaints,
            otherIllnesses = request.otherIllnesses,
            moreDetails = request.moreDetails,
            visualCheckup = request.visualCheckup,

            occlusion = request.occlusion,
            oralCavityCondition = request.oralCavityCondition,
            xrayLabData = request.xrayLabData,
            treatment = request.treatment,
            treatmentResult = request.treatmentResult,
            recommendations = request.recommendations,
            doctorName = doctorName,
            doctorClinic = doctorClinic,
            formNumber = nextNumber,

            dentalChart = request.dentalChart,
            followups = normalizeFollowups(request.followups, doctorName),
            document = null
        )

        val saved = forms.save(form)
        return toDto(saved)
    }

    /**
     * Doctor asks patient to sign form 025-2. Sends in-app + FCM notification with [Notification.patientFormId].
     */
    @Transactional
    fun requestPatientSignature(patientId: Long, formId: Long, doctor: DoctorProfile): PatientFormDto {
        val form = forms.findById(formId)
            .orElseThrow { IllegalArgumentException("Form not found: $formId") }
        if (form.patient?.id != patientId) {
            throw IllegalArgumentException("Form does not belong to patient $patientId")
        }
        if (form.templateId != "025-2") {
            throw IllegalArgumentException("Patient signature is only supported for template 025-2")
        }
        if (form.patientSignatureImage != null) {
            throw IllegalArgumentException("Form is already signed by the patient")
        }
        form.signatureRequested = true
        val saved = forms.save(form)
        val patient = saved.patient
            ?: throw IllegalStateException("Form has no patient")

        val doctorName = "${doctor.firstName ?: ""} ${doctor.lastName ?: ""}".trim().ifEmpty { "Doctor" }
        val notif = Notification(
            patient = patient,
            doctor = doctor,
            title = "Signature Requested",
            message = "Dr. $doctorName is requesting your signature on medical form 025-2.",
            type = Notification.Type.SIGNATURE_REQUESTED,
            appointmentId = null,
            patientFormId = saved.id
        )
        val savedNotif = notifications.save(notif)
        patient.fcmToken?.let { token ->
            fcmService.sendPatientNotification(
                token,
                savedNotif,
                mapOf(
                    "route" to "/bookings/sign-form/${saved.id}",
                    "patientFormId" to saved.id.toString()
                )
            )
        }
        return toDto(saved)
    }

    @Transactional(readOnly = true)
    fun getForPatientSigning(formId: Long, patientId: Long): PatientFormDto {
        val form = forms.findById(formId)
            .orElseThrow { IllegalArgumentException("Form not found: $formId") }
        if (form.patient?.id != patientId) {
            throw IllegalArgumentException("Form does not belong to this patient")
        }
        if (form.templateId != "025-2") {
            throw IllegalArgumentException("Signing is only available for form 025-2")
        }
        if (!form.signatureRequested) {
            throw IllegalArgumentException("No signature has been requested for this form")
        }
        return toDto(form)
    }

    @Transactional
    fun submitPatientSignature(formId: Long, patientId: Long, signatureImageBase64: String) {
        val form = forms.findById(formId)
            .orElseThrow { IllegalArgumentException("Form not found: $formId") }
        if (form.patient?.id != patientId) {
            throw IllegalArgumentException("Form does not belong to this patient")
        }
        if (!form.signatureRequested) {
            throw IllegalArgumentException("Signature was not requested for this form")
        }
        if (form.patientSignatureImage != null) {
            throw IllegalArgumentException("Signature already submitted")
        }
        val base64 = signatureImageBase64.trim()
        if (base64.isBlank()) {
            throw IllegalArgumentException("Signature image is required")
        }
        form.patientSignatureImage = base64
        form.patientSignedAt = Instant.now()
        forms.save(form)
    }

    @Transactional
    fun update(
        formId: Long,
        request: PatientFormDto,
        doctorName: String,
        doctorClinic: String?
    ): PatientFormDto {
        val form = forms.findById(formId)
            .orElseThrow { IllegalArgumentException("Form not found: $formId") }

        form.templateId = request.templateId
        form.date = request.date
        form.fullName = request.fullName
        form.gender = request.gender
        form.address = request.address
        form.age = request.age
        form.job = request.job
        form.diagnosis = request.diagnosis
        form.diagnosisCode = request.diagnosisCode
        form.diagnosisDisplay = request.diagnosisDisplay
        form.diagnosisSystem = request.diagnosisSystem
        form.complaints = request.complaints
        form.otherIllnesses = request.otherIllnesses
        form.moreDetails = request.moreDetails
        form.visualCheckup = request.visualCheckup

        form.occlusion = request.occlusion
        form.oralCavityCondition = request.oralCavityCondition
        form.xrayLabData = request.xrayLabData
        form.treatment = request.treatment
        form.treatmentResult = request.treatmentResult
        form.recommendations = request.recommendations
        form.doctorName = doctorName
        form.doctorClinic = doctorClinic
        // formNumber stays stable for edits

        form.dentalChart = request.dentalChart
        form.followups = normalizeFollowups(request.followups, doctorName)

        val saved = forms.save(form)
        return toDto(saved)
    }

    @Transactional
    fun linkDocument(formId: Long, documentId: Long): PatientFormDto {
        val form = forms.findById(formId)
            .orElseThrow { IllegalArgumentException("Form not found: $formId") }
        val document = docs.findById(documentId)
            .orElseThrow { IllegalArgumentException("Document not found: $documentId") }

        // Documents that back doctor-side forms (e.g. 025-2) must stay
        // doctor-private regardless of what category (if any) was supplied at
        // upload time. We tag them as FORM_025_2 here and force the
        // team-visibility flag off so the new "share with all doctors of the
        // patient" rule does not apply to forms.
        document.category = PatientDocumentCategory.FORM_025_2.code
        document.isSharedWithTeam = false
        docs.save(document)

        form.document = document
        val saved = forms.save(form)
        return toDto(saved)
    }

    @Transactional
    fun delete(formId: Long) {
        forms.deleteById(formId)
    }

    private fun normalizeFollowups(
        followups: List<PatientFormFollowupDto>,
        doctorName: String
    ): List<Map<String, String>> {
        return followups.map { f ->
            mapOf(
                "date" to f.date.toString(),
                "clinicalFindings" to f.clinicalFindings,
                "doctorName" to (f.doctorName ?: doctorName)
            )
        }
    }

    private fun toDto(form: PatientForm): PatientFormDto {
        val followups = (form.followups ?: emptyList()).map { m ->
            PatientFormFollowupDto(
                date = java.time.LocalDate.parse(m["date"] ?: java.time.LocalDate.now().toString()),
                clinicalFindings = (m["clinicalFindings"] ?: ""),
                doctorName = m["doctorName"]
            )
        }

        return PatientFormDto(
            id = requireNotNull(form.id) { "Form must have an id" },
            patientId = requireNotNull(form.patient?.id) { "Form must have a patient" },
            templateId = form.templateId,
            date = form.date,
            fullName = form.fullName,
            gender = form.gender,
            address = form.address,
            age = form.age,
            job = form.job,
            diagnosis = form.diagnosis,
            diagnosisCode = form.diagnosisCode,
            diagnosisDisplay = form.diagnosisDisplay,
            diagnosisSystem = form.diagnosisSystem,
            complaints = form.complaints,
            otherIllnesses = form.otherIllnesses,
            moreDetails = form.moreDetails,
            visualCheckup = form.visualCheckup,

            occlusion = form.occlusion,
            oralCavityCondition = form.oralCavityCondition,
            xrayLabData = form.xrayLabData,
            treatment = form.treatment,
            treatmentResult = form.treatmentResult,
            recommendations = form.recommendations,
            doctorName = form.doctorName,
            doctorClinic = form.doctorClinic,
            formNumber = form.formNumber,

            dentalChart = (form.dentalChart ?: emptyMap()),
            followups = followups,
            documentId = form.document?.id,
            signatureRequested = form.signatureRequested,
            patientSignedAt = form.patientSignedAt?.toString(),
            patientSignatureImageBase64 = form.patientSignatureImage
        )
    }
}