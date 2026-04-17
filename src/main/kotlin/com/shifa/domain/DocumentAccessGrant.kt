package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "document_access_grants")
class DocumentAccessGrant(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    val document: PatientDocument,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @Enumerated(EnumType.STRING)
    @Column(name = "granted_by", nullable = false, length = 20)
    val grantedBy: GrantedByType,

    @Column(name = "granted_at", nullable = false)
    val grantedAt: Instant = Instant.now()
) {
    enum class GrantedByType { doctor, patient }
}
