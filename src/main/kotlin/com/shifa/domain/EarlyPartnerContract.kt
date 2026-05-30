package com.shifa.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.OffsetDateTime

@Entity
@Table(name = "early_partner_contract")
class EarlyPartnerContract(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_profile_id", nullable = false, unique = true)
    val doctorProfile: DoctorProfile,

    @Column(name = "contract_seq", nullable = false, unique = true)
    val contractSeq: Int,

    @Column(name = "contract_number", nullable = false, unique = true)
    val contractNumber: String,

    @Column(name = "effective_date", nullable = false)
    var effectiveDate: LocalDate,

    @Column(name = "term_months", nullable = false)
    var termMonths: Int = 6,

    @Column(name = "partner_full_name", nullable = false)
    var partnerFullName: String,

    @Column(name = "partner_clinic")
    var partnerClinic: String? = null,

    @Column(name = "partner_phone")
    var partnerPhone: String? = null,

    @Column(name = "partner_email")
    var partnerEmail: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
