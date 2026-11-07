package com.shifa.notifications.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/** One doctor device's FCM token; a doctor gets every push on each of them. */
@Entity
@Table(name = "doctor_push_tokens")
class DoctorPushToken(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /** The doctor signed in on the device (moves when another doctor signs in there). */
    @Column(name = "doctor_profile_id", nullable = false)
    var doctorProfileId: Long,

    @Column(nullable = false, unique = true, length = 512)
    val token: String,

    /** `android`, `ios` or `web`; null when the app didn't say. */
    @Column(length = 16)
    var platform: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: OffsetDateTime = OffsetDateTime.now(),
)
