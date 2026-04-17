package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity @Table(name = "appointments")
class Appointment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne @JoinColumn(name="doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @ManyToOne @JoinColumn(name="patient_id", nullable = false)
    val patient: PatientProfile,

    @Column(name="start_at", nullable = false)
    var startAt: Instant,

    @Column(name="end_at", nullable = false)
    var endAt: Instant,

    @Column(nullable = false)
    var location: String, // "Video Consultation" or clinic

    var reason: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: Status = Status.REQUESTED,

    @Column(name = "signature_requested", nullable = false)
    var signatureRequested: Boolean = false,

    @Column(name = "patient_signature_image", columnDefinition = "TEXT")
    var patientSignatureImage: String? = null,

    @Column(name = "patient_signed_at")
    var patientSignedAt: Instant? = null
) {
    enum class Status { REQUESTED, CONFIRMED, CANCELLED, COMPLETED }
}
