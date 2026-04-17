package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "conversations")
open class Conversation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null,

    // One participant is always a doctor (from User with DOCTOR role)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_user_id", nullable = false)
    open var doctorUser: User? = null,

    // Other participant can be a doctor or patient
    // If it's a doctor, use doctorParticipantId
    // If it's a patient, use patientParticipantId
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_participant_id")
    open var doctorParticipant: User? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_participant_id")
    open var patientParticipant: PatientProfile? = null,

    @Column(name = "last_message_at")
    open var lastMessageAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    open var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    open var updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    protected constructor() : this(
        id = null,
        doctorUser = null,
        doctorParticipant = null,
        patientParticipant = null,
        lastMessageAt = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    @PreUpdate
    fun onUpdate() {
        updatedAt = OffsetDateTime.now()
    }

    // Note: Helper methods for getting participant name/photo should be implemented
    // in the service layer where we can properly fetch DoctorProfile via repository
}
