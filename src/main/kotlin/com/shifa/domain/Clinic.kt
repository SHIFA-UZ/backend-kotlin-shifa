package com.shifa.domain

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "clinics")
class Clinic(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var name: String,

    var phone: String? = null,
    var email: String? = null,

    @Column(columnDefinition = "TEXT")
    var address: String? = null,

    @Column(name = "time_zone", nullable = false, length = 64)
    var timeZone: String = "Asia/Tashkent",

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
