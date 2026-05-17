package com.shifa.domain

import jakarta.persistence.*
import java.time.Instant

@Entity @Table(name = "appointments")
class Appointment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne @JoinColumn(name="doctor_id", nullable = false)
    val doctor: DoctorProfile,

    @ManyToOne @JoinColumn(name="patient_id", nullable = false)
    val patient: PatientProfile,

    /** User (doctor or clinic staff) who created this booking when acting on behalf of [doctor]. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booked_by_user_id")
    var bookedByUser: User? = null,

    @Column(name="start_at", nullable = false)
    var startAt: Instant,

    @Column(name="end_at", nullable = false)
    var endAt: Instant,

    @Column(nullable = false)
    var location: String, // Human-readable label: "Video Consultation" or the clinic/location name

    /**
     * Structured reference to the doctor's location for this appointment. Null for video
     * consultations or legacy appointments that predate multi-location support.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    var locationRef: DoctorLocation? = null,

    var reason: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: Status = Status.REQUESTED,

    @Column(name = "payment_amount_minor")
    var paymentAmountMinor: Long? = null,

    @Column(name = "payment_currency", length = 3)
    var paymentCurrency: String? = null,

    @Column(name = "service_id")
    var serviceId: Long? = null,

    @Column(name = "service_title", length = 160)
    var serviceTitle: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 32)
    var paymentStatus: PaymentStatus = PaymentStatus.NOT_REQUIRED,

    @Column(name = "signature_requested", nullable = false)
    var signatureRequested: Boolean = false,

    @Column(name = "patient_signature_image", columnDefinition = "TEXT")
    var patientSignatureImage: String? = null,

    @Column(name = "patient_signed_at")
    var patientSignedAt: Instant? = null,

    /**
     * JSON payload for dental specialty visit documentation (FDI tooth codes → services, discount, notes).
     * Schema is owned by the doctor app; see [com.shifa.web.AppointmentController] dental endpoints.
     */
    @Column(name = "dental_documentation", columnDefinition = "TEXT")
    var dentalDocumentation: String? = null
) {
    enum class Status { REQUESTED, CONFIRMED, IN_PROGRESS, CANCELLED, COMPLETED }
    enum class PaymentStatus { NOT_REQUIRED, PENDING, PAID, FAILED, REFUNDED }
}
