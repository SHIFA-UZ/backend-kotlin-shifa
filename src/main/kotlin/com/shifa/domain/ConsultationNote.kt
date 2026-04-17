package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * Official consultation note (created when doctor confirms an AI draft or writes manually).
 * SOAP-style fields: subjective, assessment, plan; or full body when unstructured.
 */
@Entity
@Table(name = "consultation_notes")
class ConsultationNote(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "doctor_id", nullable = false)
    var doctorId: Long,

    @Column(name = "patient_id", nullable = false)
    var patientId: Long,

    @Column(name = "appointment_id", nullable = true)
    var appointmentId: Long? = null,

    @Column(name = "ai_draft_note_id", nullable = true)
    var aiDraftNoteId: java.util.UUID? = null,

    @Column(columnDefinition = "TEXT")
    var subjective: String? = null,

    @Column(columnDefinition = "TEXT")
    var assessment: String? = null,

    @Column(columnDefinition = "TEXT")
    var plan: String? = null,

    /** Full note body when not structured (or combined content). */
    @Column(columnDefinition = "TEXT")
    var body: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "source", nullable = false, length = 32)
    var source: String = "MANUAL" // MANUAL | AI_DRAFT
)
