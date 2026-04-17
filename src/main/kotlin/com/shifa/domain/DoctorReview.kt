package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "doctor_reviews")
class DoctorReview(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne
    @JoinColumn(name = "doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    val patient: PatientProfile,

    @ManyToOne
    @JoinColumn(name = "appointment_id", nullable = true)
    val appointment: Appointment? = null,

    @Column(nullable = false)
    var rating: Int, // 1-5

    @Column(columnDefinition = "TEXT")
    var comment: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    init {
        require(rating in 1..5) { "Rating must be between 1 and 5" }
    }
}
