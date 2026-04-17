package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity @Table(name="doctor_billing")
class DoctorBilling(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne @JoinColumn(name="doctor_id", unique = true, nullable = false)
    val doctor: DoctorProfile,

    var billingName: String? = null,
    var billingEmail: String? = null,
    var iban: String? = null,
    var taxId: String? = null,

    @Column(nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
