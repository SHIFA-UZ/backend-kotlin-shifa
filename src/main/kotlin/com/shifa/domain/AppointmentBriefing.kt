package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "appointment_briefings")
class AppointmentBriefing(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false, unique = true)
    val appointment: Appointment,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: Status = Status.PENDING,

    @Column(name = "briefing_text", columnDefinition = "TEXT")
    var briefingText: String? = null,

    @Column(name = "document_count", nullable = false)
    var documentCount: Int = 0,

    @Column(name = "generated_at")
    var generatedAt: Instant? = null,

    @Column(columnDefinition = "TEXT")
    var error: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    enum class Status {
        PENDING,
        /** Worker claimed this row; prevents duplicate PDF/OpenAI work. */
        GENERATING,
        READY,
        FAILED,
        SKIPPED,
    }
}
