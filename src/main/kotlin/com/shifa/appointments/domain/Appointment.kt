package com.shifa.appointments.domain

import com.shifa.doctors.domain.DoctorLocation
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.domain.PatientProfile
import com.shifa.doctors.domain.ServicePaymentType
import com.shifa.clinics.domain.TreatmentPlanLine
import com.shifa.auth.domain.User

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

    @Column(name = "is_video_consultation", nullable = false)
    var isVideoConsultation: Boolean = false,

    /** Snapshot of the service payment type at booking. Null for free and legacy rows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 16)
    var paymentType: ServicePaymentType? = null,

    /** When a pay-now hold ends. Null once the visit is confirmed, failed, or not a hold. */
    @Column(name = "hold_expires_at")
    var holdExpiresAt: Instant? = null,

    /** Paid, but the visit was not kept. Staff refund manually. */
    @Column(name = "refund_required", nullable = false)
    var refundRequired: Boolean = false,

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
     * Schema is owned by the doctor app; see [com.shifa.appointments.api.AppointmentController] dental endpoints.
     */
    @Column(name = "dental_documentation", columnDefinition = "TEXT")
    var dentalDocumentation: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_treatment_plan_line_id")
    var linkedTreatmentPlanLine: TreatmentPlanLine? = null,

    /** Set when DevSMS 24h reminder was sent; cleared on reschedule. */
    @Column(name = "sms_reminder_sent_at")
    var smsReminderSentAt: Instant? = null,

    /** Why the doctor's side cancelled (`POST /appointments/{id}/cancel`); null for other cancellations and old rows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason_code", length = 32)
    var cancelReasonCode: CancelReason? = null,

    /** The doctor's note to the patient with [cancelReasonCode]. */
    @Column(name = "cancel_note", columnDefinition = "TEXT")
    var cancelNote: String? = null,

    /** The earlier visit of the same patient this one follows up (BE-20); null for an ordinary booking. */
    @Column(name = "follow_up_of_appointment_id")
    var followUpOfAppointmentId: Long? = null,
) {
    enum class Status {
        REQUESTED, CONFIRMED, IN_PROGRESS, CANCELLED, COMPLETED, NO_SHOW,
        PENDING_PAYMENT, EXPIRED, PAYMENT_FAILED,
    }
    enum class PaymentStatus { NOT_REQUIRED, PENDING, PAID, FAILED, REFUNDED, PAY_IN_CLINIC }
    enum class CancelReason { DOCTOR_UNAVAILABLE, PATIENT_REQUEST, RESCHEDULED, OTHER }

    /** A confirmed or started visit can be marked NO_SHOW from its start until [NO_SHOW_GRACE] after its end. */
    fun canMarkNoShow(now: Instant): Boolean =
        status in NO_SHOW_STATUSES && !now.isBefore(startAt) && !now.isAfter(endAt.plus(NO_SHOW_GRACE))

    /** When the video room can be joined; null for a visit in person. */
    fun videoJoinWindow(): VideoJoinWindow? = if (isVideoConsultation) VideoJoinWindow.of(startAt, endAt) else null

    /** Slot is free for someone else. */
    fun releasesSlot(): Boolean =
        status == Status.CANCELLED || status == Status.EXPIRED || status == Status.PAYMENT_FAILED

    companion object {
        val NO_SHOW_GRACE: java.time.Duration = java.time.Duration.ofHours(24)
        private val NO_SHOW_STATUSES = setOf(Status.CONFIRMED, Status.IN_PROGRESS)
    }
}
