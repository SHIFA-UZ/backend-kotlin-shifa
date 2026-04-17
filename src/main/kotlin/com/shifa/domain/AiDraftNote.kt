package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ai_draft_notes")
class AiDraftNote(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(name = "doctor_id", nullable = false)
    var doctorId: Long,

    @Column(name = "patient_id", nullable = true)
    var patientId: Long? = null,

    @Column(name = "consultation_id", nullable = true)
    var consultationId: Long? = null,

    @Column(name = "ai_response_text", nullable = false, columnDefinition = "TEXT")
    var aiResponseText: String,

    @Column(name = "ai_label", nullable = false, length = 255)
    var aiLabel: String,

    /**
     * Optional ICD-10 suggestions produced from the AI scribe output.
     * JSON string payload (list) to keep pipeline simple and backward compatible.
     */
    @Column(name = "icd_suggestions_json", nullable = true, columnDefinition = "TEXT")
    var icdSuggestionsJson: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: Status = Status.GENERATED,

    @Column(name = "model_version", nullable = false, length = 64)
    var modelVersion: String,

    @Column(name = "prompt_version", nullable = false, length = 64)
    var promptVersion: String
) {
    enum class Status { GENERATED, CONFIRMED, DISCARDED }
}
