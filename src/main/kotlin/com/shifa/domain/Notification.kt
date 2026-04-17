package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "notifications")
class Notification(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne
    @JoinColumn(name = "patient_id")
    val patient: PatientProfile? = null,

    @ManyToOne
    @JoinColumn(name = "doctor_id")
    val doctor: DoctorProfile? = null,

    @Column(nullable = false)
    val title: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val message: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: Type,

    @Column(name = "appointment_id")
    val appointmentId: Long? = null,

    @Column(name = "document_access_request_id")
    val documentAccessRequestId: Long? = null,

    @Column(name = "task_id")
    val taskId: Long? = null,

    /** Document ID for document-access notifications; enables deep link to patient document. */
    @Column(name = "document_id")
    val documentId: Long? = null,

    /** Patient ID (document owner) for document-access notifications; enables deep link to patient screen. */
    @Column(name = "document_patient_id")
    val documentPatientId: Long? = null,

    /** Document title for document-access notifications; used when opening document viewer. */
    @Column(name = "document_title", length = 512)
    val documentTitle: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "read_at")
    var readAt: Instant? = null
) {
    enum class Type {
        CHAT_MESSAGE,
        APPOINTMENT_CANCELLED,
        APPOINTMENT_CHANGED,
        APPOINTMENT_REMINDER,
        APPOINTMENT_BOOKED_BY_PATIENT,
        APPOINTMENT_CANCELLED_BY_PATIENT,
        APPOINTMENT_RESCHEDULED_BY_PATIENT,
        APPOINTMENT_ASSIGNED,
        SIGNATURE_REQUESTED,
        TASK_REMINDER,
        TASK_COMPLETED,
        DOCUMENT_ACCESS_REQUEST,
        DOCUMENT_ACCESS_APPROVED,
        DOCUMENT_ACCESS_REJECTED,
        TASK_ASSIGNED,
        TASK_CANCELLED,
        GENERAL,
        AI_SCRIBE_READY,
        AI_VISIT_SUMMARY_READY
    }

    fun isRead(): Boolean = readAt != null
}
