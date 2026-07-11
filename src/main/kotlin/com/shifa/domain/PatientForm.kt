package com.shifa.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(name = "patient_forms")
open class PatientForm(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    open var patient: PatientProfile? = null,

    @Column(name = "template_id", nullable = false)
    open var templateId: String = "",

    @Column(name = "date", nullable = false)
    open var date: LocalDate = LocalDate.now(),

    @Column(name = "full_name", nullable = false)
    open var fullName: String = "",

    @Column(name = "gender")
    open var gender: String? = null,

    @Column(name = "address", columnDefinition = "TEXT")
    open var address: String? = null,

    @Column(name = "age")
    open var age: Int? = null,

    @Column(name = "job", columnDefinition = "TEXT")
    open var job: String? = null,

    @Column(name = "diagnosis", columnDefinition = "TEXT")
    open var diagnosis: String? = null,

    // --- ICD-10 structured diagnosis (optional; backward compatible with free-text) ---
    @Column(name = "diagnosis_code", length = 16)
    open var diagnosisCode: String? = null,

    @Column(name = "diagnosis_display", columnDefinition = "TEXT")
    open var diagnosisDisplay: String? = null,

    @Column(name = "diagnosis_system", length = 16)
    open var diagnosisSystem: String? = null,

    @Column(name = "complaints", columnDefinition = "TEXT")
    open var complaints: String? = null,

    @Column(name = "other_illnesses", columnDefinition = "TEXT")
    open var otherIllnesses: String? = null,

    @Column(name = "more_details", columnDefinition = "TEXT")
    open var moreDetails: String? = null,

    @Column(name = "visual_checkup", columnDefinition = "TEXT")
    open var visualCheckup: String? = null,

    // ---- New 025-2 fields ----
    @Column(name = "occlusion", columnDefinition = "TEXT")
    open var occlusion: String? = null,

    @Column(name = "oral_cavity_condition", columnDefinition = "TEXT")
    open var oralCavityCondition: String? = null,

    @Column(name = "xray_lab_data", columnDefinition = "TEXT")
    open var xrayLabData: String? = null,

    @Column(name = "treatment", columnDefinition = "TEXT")
    open var treatment: String? = null,

    @Column(name = "treatment_result", columnDefinition = "TEXT")
    open var treatmentResult: String? = null,

    @Column(name = "recommendations", columnDefinition = "TEXT")
    open var recommendations: String? = null,

    @Column(name = "doctor_name", columnDefinition = "TEXT")
    open var doctorName: String? = null,

    @Column(name = "doctor_clinic", columnDefinition = "TEXT")
    open var doctorClinic: String? = null,

    @Column(name = "form_number")
    open var formNumber: Int? = null,

    /** Dental chart mapping per tooth. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dental_chart", columnDefinition = "jsonb")
    open var dentalChart: Map<String, String>? = emptyMap(),

    /** Follow-up rows for returning patient. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "followups", columnDefinition = "jsonb")
    open var followups: List<Map<String, String>>? = emptyList(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    open var document: PatientDocument? = null,

    /** Doctor who created this form record (owner); used for RAG access control on 025-2. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_doctor_id")
    open var createdByDoctor: DoctorProfile? = null,

    /** Clinical Engine scenario id (hidden analytics field). */
    @Column(name = "clinical_disease_id", length = 32)
    open var clinicalDiseaseId: String? = null,

    /** Clinical Engine chip selections for edit restore (hidden). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "clinical_chip_selections", columnDefinition = "jsonb", nullable = false)
    open var clinicalChipSelections: List<Map<String, Any>> = emptyList(),

    @Column(name = "signature_requested", nullable = false)
    open var signatureRequested: Boolean = false,

    @Column(name = "patient_signature_image", columnDefinition = "TEXT")
    open var patientSignatureImage: String? = null,

    @Column(name = "patient_signed_at")
    open var patientSignedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    open var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    protected constructor() : this(
        id = null,
        patient = null,
        templateId = "",
        date = LocalDate.now(),
        fullName = "",
        gender = null,
        address = null,
        age = null,
        job = null,
        diagnosis = null,
        diagnosisCode = null,
        diagnosisDisplay = null,
        diagnosisSystem = null,
        complaints = null,
        otherIllnesses = null,
        moreDetails = null,
        visualCheckup = null,
        occlusion = null,
        oralCavityCondition = null,
        xrayLabData = null,
        treatment = null,
        treatmentResult = null,
        recommendations = null,
        doctorName = null,
        doctorClinic = null,
        formNumber = null,
        dentalChart = emptyMap(),
        followups = emptyList(),
        document = null,
        createdByDoctor = null,
        clinicalDiseaseId = null,
        clinicalChipSelections = emptyList(),
        signatureRequested = false,
        patientSignatureImage = null,
        patientSignedAt = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    @PreUpdate
    fun onUpdate() {
        updatedAt = OffsetDateTime.now()
    }
}