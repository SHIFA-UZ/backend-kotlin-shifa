package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "doctor_service_prices")
class DoctorServicePrice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    val service: DoctorService,

    /** When null, this row is the default price for all practice locations (unless overridden). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    var location: DoctorLocation? = null,

    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long,

    @Column(nullable = false, length = 3)
    var currency: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
