package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(
    name = "clinic_memberships",
    uniqueConstraints = [UniqueConstraint(columnNames = ["clinic_id", "user_id"])]
)
class ClinicMembership(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clinic_id", nullable = false)
    val clinic: Clinic,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_role", nullable = false, length = 32)
    var membershipRole: MembershipRole,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_profile_id")
    var doctorProfile: DoctorProfile? = null,

    @Column(nullable = false)
    var active: Boolean = true,

    /** Per-doctor revenue share override at this clinic (0–100); null uses clinic default. */
    @Column(name = "doctor_revenue_share_percent", columnDefinition = "SMALLINT")
    var doctorRevenueSharePercent: Int? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
) {
    enum class MembershipRole {
        OWNER,
        DOCTOR,
        STAFF,
        /** Administrative access to clinic roster and operations (full patient list per product rules). */
        CLINIC_ADMIN,
        /** Front desk — full clinic patient roster and scheduling. */
        RECEPTIONIST,
        /** Limited operational access (defaults to scoped roster like a doctor in workspace APIs). */
        NURSE,
    }
}
