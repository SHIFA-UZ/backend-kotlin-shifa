
// src/main/kotlin/com/shifa/domain/PatientDocument.kt
package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate

@Entity
@Table(name = "patient_documents")
open class PatientDocument(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    open var id: Long? = null,                                     // ← non-null, default 0L (assigned by DB)

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)           // FK to patient_profiles(id)
    open var patient: PatientProfile? = null,                            // ← non-null

    @Column(name = "title", nullable = false, length = 255)
    open var title: String = "",                                      // ← non-null

    @Column(name = "date", nullable = false)
    open var date: LocalDate = LocalDate.now(),                                    // ← non-null

    @Column(name = "file_path", nullable = false, columnDefinition = "text")
    open var filePath: String = "",                                    // ← non-null

    @Column(name = "is_chat_attachment", nullable = false)
    open var isChatAttachment: Boolean = false,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_doctor_id")
    open var uploadedByDoctor: DoctorProfile? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_patient_profile_id")
    open var uploadedByPatientProfile: PatientProfile? = null
) {
    protected constructor() : this(
        id = null,
        patient = null,
        title = "",
        date = LocalDate.now(),
        filePath = "",
        isChatAttachment = false,
        uploadedByDoctor = null,
        uploadedByPatientProfile = null
    )
}
