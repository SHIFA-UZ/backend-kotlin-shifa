package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "admin_profiles")
class AdminProfile(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,

    @Column(name = "first_name", nullable = false)
    var firstName: String,

    @Column(name = "last_name", nullable = false)
    var lastName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "admin_level", nullable = false)
    var adminLevel: AdminLevel = AdminLevel.ADMIN,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    fun getFullName(): String = "$firstName $lastName".trim()
}

enum class AdminLevel {
    READ_ONLY,    // Can view but not modify
    ADMIN,        // Standard admin permissions
    SUPER_ADMIN   // Full access including system config
}
