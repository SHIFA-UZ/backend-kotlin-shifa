package com.shifa.service

import com.shifa.domain.PatientDocumentCategory
import com.shifa.domain.PatientForm
import com.shifa.repo.PatientDocumentRepository
import com.shifa.repo.PatientFormRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.web.dto.PatientFormDto
import com.shifa.web.dto.PatientFormFollowupDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PatientFormService(
    private val profiles: PatientProfileRepository,
    private val forms: PatientFormRepository,
    private val docs: PatientDocumentRepository
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
            documentId = form.document?.id
        )
    }
}