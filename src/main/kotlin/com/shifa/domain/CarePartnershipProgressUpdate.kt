package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "care_partnership_progress_updates")
class CarePartnershipProgressUpdate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "partnership_id", nullable = false)
    val partnership: CarePartnership,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_doctor_id", nullable = false)
    val authorDoctor: DoctorProfile,

    @Column(nullable = false, columnDefinition = "TEXT")
    val body: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
