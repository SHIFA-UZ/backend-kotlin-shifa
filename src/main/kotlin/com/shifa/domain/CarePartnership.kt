package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "care_partnerships")
class CarePartnership(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    val patient: PatientProfile,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiating_doctor_id", nullable = false)
    val initiatingDoctor: DoctorProfile,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "partner_doctor_id", nullable = false)
    val partnerDoctor: DoctorProfile,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: Status = Status.PENDING,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "originating_appointment_id")
    var originatingAppointment: Appointment? = null,

    @Column(name = "specialty_requested", length = 255)
    var specialtyRequested: String? = null,

    @Column(columnDefinition = "TEXT")
    var message: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    enum class Status {
        PENDING,
        ACTIVE,
        DECLINED,
        COMPLETED,
        CANCELLED,
    }
}
