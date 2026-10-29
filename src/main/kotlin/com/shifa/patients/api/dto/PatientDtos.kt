package com.shifa.patients.api.dto


data class UpdateProfileRequest(
    val firstName: String?,
    val lastName: String?,
    val birthDate: String?,
    val gender: String?,
    val address: String?,
    val language: String?,
    val photoUrl: String?,
    val latitude: Double?,
    val longitude: Double?,
    // Structured location fields
    val locationCountry: String?,
    val locationRegion: String?,
    val locationDistrict: String?,
    val locationCity: String?,
    val locationPostalCode: String?,
    val locationStreetAddress: String?,
    val timeZone: String?
)

data class AttachDocumentsRequest(val documentIds: List<Long>)

data class PaymentCheckoutDto(
    val id: Long,
    val provider: String,
    val checkoutUrl: String,
    val expiresAt: String?,
)

/** startAt: ISO 8601 UTC (e.g. 2026-02-12T13:00:00Z). */
data class BookAppointmentRequest(
    val doctorId: Long,
    val startAt: String,
    val slotMinutes: Int = 30,
    val reason: String?,
    val isVideo: Boolean = false,
    val serviceId: Long? = null,
    /**
     * Optional practice location id. If the doctor has multiple structured locations, the
     * patient MUST pick one (unless the booking is a video consultation). For single-location
     * doctors (or legacy doctors without structured locations), this can be omitted.
     */
    val locationId: Long? = null,
    /**
     * Optional patient document IDs already uploaded via POST /patients/me/documents.
     * Linked to the new appointment as pre-appointment attachments.
     */
    val documentIds: List<Long>? = null,
    /**
     * When the patient is moving an existing booking, the id of the appointment
     * to cancel after the new one is created. Doctor gets one reschedule push
     * instead of booked + cancelled.
     */
    val rescheduleAppointmentId: Long? = null,
    /**
     * CLICK or STRIPE. The new app sends this for pay-now, including when only one method is listed.
     * Omitted by the current app, which still books first and pays from the appointments screen.
     */
    val paymentProvider: String? = null,
    /** "app": the gateway return pages redirect back into the patient app (ask 12). */
    val platform: String? = null,
)
