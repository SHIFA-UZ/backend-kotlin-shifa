package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

/**
 * Multi-role support: One user can have multiple roles (DOCTOR, PATIENT, ADMIN).
 * This table allows a single user to exist in both doctor_profiles and patient_profiles.
 */
@Entity
@Table(
    name = "user_roles",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "role"])]
)
class UserRole(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val role: Role,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
