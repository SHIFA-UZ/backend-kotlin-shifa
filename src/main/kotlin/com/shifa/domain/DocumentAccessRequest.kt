package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "document_access_requests")
class DocumentAccessRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    val document: PatientDocument,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requesting_doctor_id", nullable = false)
    val requestingDoctor: DoctorProfile,

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    val ownerType: OwnerType,

    @Column(name = "owner_id", nullable = false)
    val ownerId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: Status = Status.pending,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {
    enum class OwnerType { doctor, patient }
    enum class Status { pending, approved, rejected }
}
