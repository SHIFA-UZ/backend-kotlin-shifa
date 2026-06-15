package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity @Table(name="doctor_settings")
class DoctorSettings(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne @JoinColumn(name="doctor_id", unique = true, nullable = false)
    val doctor: DoctorProfile,

    var country: String? = null,
    var language: String? = null,
    @Column(name="two_factor", nullable = false) var twoFactor: Boolean = false,
    @Column(name="encrypted_docs", nullable = false) var encryptedDocs: Boolean = true,
    /** Main shell tab key shown on app launch (e.g. home, calendar). */
    @Column(name="default_start_tab", nullable = false) var defaultStartTab: String = "home",

    @Column(nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
