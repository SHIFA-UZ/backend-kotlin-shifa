// src/main/kotlin/com/shifa/web/PatientController.kt
package com.shifa.web

import com.shifa.config.AppProperties
import com.shifa.config.PaymentProperties
import com.shifa.payment.service.PaymentService
import java.time.ZoneOffset
import com.shifa.domain.Appointment
import com.shifa.domain.ServicePaymentType
import com.shifa.domain.Notification
import com.shifa.domain.PatientProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorLocationRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.PatientPrincipal
import com.shifa.service.BookingPaymentPolicy
import com.shifa.service.DoctorServicePricing
import com.shifa.service.FcmService
import com.shifa.service.NotificationFormatting
import com.shifa.service.PatientDocumentService
import com.shifa.service.SlotAvailabilityService
import com.shifa.service.PatientProfileMapper
import com.shifa.web.dto.PatientDocumentDto
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.*
import java.time.format.DateTimeFormatter

private fun normalizeAvatarUrl(avatarUrl: String?, baseUrl: String): String? {
    val trimmed = avatarUrl?.trim() ?: return null
    val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
    return if (isAbs) trimmed
    else "${baseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
}

@RestController
@RequestMapping("/api/patients/me")
class PatientController(
    private val patientProfiles: PatientProfileRepository,
    private val appointments: AppointmentRepository,
    private val profileMapper: PatientProfileMapper,
    private val appProps: AppProperties,
    private val doctorProfiles: DoctorProfileRepository,
    private val doctorLocations: DoctorLocationRepository,
    private val doctorServices: DoctorServiceRepository,
    private val doctorServicePrices: DoctorServicePriceRepository,
    private val patientDocumentService: PatientDocumentService,
    private val appointmentBriefingService: com.shifa.service.AppointmentBriefingService,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
    private val subscriptionTierService: com.shifa.service.SubscriptionTierService,
    private val slotAvailabilityService: SlotAvailabilityService,
    private val paymentService: PaymentService,
    private val paymentProperties: PaymentProperties,
) {

    // -------------------- Helper --------------------

    private fun currentPatientProfile(principal: PatientPrincipal): PatientProfile {
        val user = principal.user
        // Prefer by user_id for multi-role (same user as doctor and patient)
        return patientProfiles.findByUserId(user.id)
            .orElseGet {
                user.phone?.let { patientProfiles.findByPhone(it) }?.orElse(null)
                    ?: user.email?.let { patientProfiles.findByEmail(it) }?.orElse(null)
            }
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Patient profile not found for user ${user.id}"
            )
    }

    // -------------------- DTOs --------------------

    data class PatientProfileDto(
        val id: Long?,
        val fullName: String,
        val phone: String?,
        val email: String?,
        val address: String?,
        val birthDate: String?,
        val gender: String?,
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
        val timeZone: String?,
        /** Admin-managed subscription tier driving feature gating in the patient app. */
        val subscriptionTier: String?,
        /** List of feature codes available to this user under their tier. */
        val features: List<String>?
    )

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

    data class AppointmentDto(
        val id: Long?,
        val doctorId: Long?,
        val doctorName: String?,
        val doctorProfession: String?,
        val doctorClinic: String?,
        val doctorPhotoUrl: String?,
        val startAt: String,
        val endAt: String,
        val location: String,
        val reason: String?,
        val status: String,
        val paymentStatus: String? = null,
        val paymentAmountMinor: Long? = null,
        val paymentCurrency: String? = null,
        val locationId: Long? = null,
        val locationLabel: String? = null,
        val locationAddress: String? = null,
        val isVideoConsultation: Boolean = false,
        val paymentType: String? = null,
        val paymentBadge: String? = null,
        val holdExpiresAt: String? = null,
        val serviceId: Long? = null,
        val serviceTitle: String? = null,
        val isLocked24h: Boolean = false,
        val canCancel: Boolean = false,
        val canReschedule: Boolean = false,
        val refundRequired: Boolean = false,
        val payment: PaymentCheckoutDto? = null,
    )

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
    )

    // -------------------- FCM Token (push notifications) --------------------

    data class FcmTokenRequest(val fcmToken: String?)

    @PutMapping("/fcm-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateFcmToken(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody req: FcmTokenRequest
    ) {
        val profile = currentPatientProfile(principal)
        profile.fcmToken = req.fcmToken?.takeIf { it.isNotBlank() }
        patientProfiles.save(profile)
    }

    // -------------------- Profile Endpoints --------------------

    @GetMapping("/profile")
    fun getProfile(
        @AuthenticationPrincipal principal: PatientPrincipal
    ): PatientProfileDto {
        val profile = currentPatientProfile(principal)
        val owner = profile.user ?: principal.user
        return PatientProfileDto(
            id = profile.id,
            fullName = profile.fullName,
            phone = profile.phone,
            email = profile.email,
            address = profile.address,
            birthDate = profile.birthDate?.toString(),
            gender = profile.gender,
            language = profile.language,
            photoUrl = profileMapper.normalizePhotoUrl(profile.photoUrl),
            latitude = profile.latitude,
            longitude = profile.longitude,
            locationCountry = profile.locationCountry,
            locationRegion = profile.locationRegion,
            locationDistrict = profile.locationDistrict,
            locationCity = profile.locationCity,
            locationPostalCode = profile.locationPostalCode,
            locationStreetAddress = profile.locationStreetAddress,
            timeZone = profile.timeZone,
            subscriptionTier = subscriptionTierService.tierOf(owner).name,
            features = subscriptionTierService.availableFeatures(owner).map { it.name }
        )
    }

    @PatchMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody req: UpdateProfileRequest
    ): PatientProfileDto {
        val profile = currentPatientProfile(principal)

        req.firstName?.let { firstName ->
            req.lastName?.let { lastName ->
                profile.fullName = "$firstName $lastName".trim()
            }
        }
        req.birthDate?.let {
            profile.birthDate = LocalDate.parse(it)
        }
        req.gender?.let { profile.gender = it.trim().takeIf { g -> g.isNotEmpty() } }
        req.address?.let { profile.address = it }
        req.language?.let { profile.language = it }
        req.photoUrl?.let { profile.photoUrl = it }
        req.latitude?.let { profile.latitude = it }
        req.longitude?.let { profile.longitude = it }
        req.locationCountry?.let { profile.locationCountry = it }
        req.locationRegion?.let { profile.locationRegion = it }
        req.locationDistrict?.let { profile.locationDistrict = it }
        req.locationCity?.let { profile.locationCity = it }
        req.locationPostalCode?.let { profile.locationPostalCode = it }
        req.locationStreetAddress?.let { profile.locationStreetAddress = it }
        req.timeZone?.let { profile.timeZone = it.takeIf { tz -> tz.isNotBlank() } }

        val saved = patientProfiles.save(profile)
        val owner = saved.user ?: principal.user
        return PatientProfileDto(
            id = saved.id,
            fullName = saved.fullName,
            phone = saved.phone,
            email = saved.email,
            address = saved.address,
            birthDate = saved.birthDate?.toString(),
            gender = saved.gender,
            language = saved.language,
            photoUrl = profileMapper.normalizePhotoUrl(saved.photoUrl),
            latitude = saved.latitude,
            longitude = saved.longitude,
            locationCountry = saved.locationCountry,
            locationRegion = saved.locationRegion,
            locationDistrict = saved.locationDistrict,
            locationCity = saved.locationCity,
            locationPostalCode = saved.locationPostalCode,
            locationStreetAddress = saved.locationStreetAddress,
            timeZone = saved.timeZone,
            subscriptionTier = subscriptionTierService.tierOf(owner).name,
            features = subscriptionTierService.availableFeatures(owner).map { it.name }
        )
    }

    // -------------------- Appointments Endpoints --------------------

    @GetMapping("/appointments")
    fun getAppointments(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?
    ): List<AppointmentDto> {
        val profile = currentPatientProfile(principal)
        val patientId = profile.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")

        val appts = if (startDate != null && endDate != null) {
            val start = LocalDate.parse(startDate).atStartOfDay(ZoneOffset.UTC).toInstant()
            val end = LocalDate.parse(endDate).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            appointments.findByPatientIdAndDateRange(patientId, start, end)
        } else {
            appointments.findByPatientId(patientId)
        }

        val filtered = status?.let { s ->
            appts.filter { it.status.name.equals(s, ignoreCase = true) }
        } ?: appts

        val hidden = setOf(Appointment.Status.EXPIRED, Appointment.Status.PAYMENT_FAILED)
        val visible = if (status != null && hidden.any { it.name.equals(status, ignoreCase = true) }) {
            filtered
        } else {
            filtered.filter { it.status !in hidden }
        }
        return visible.map { toAppointmentDto(it) }
    }

    // GET /api/patients/me/appointments/{id} is handled by PatientAppointmentController.getById
    // (returns AppointmentSummaryDto with signatureRequested, alreadySigned for details + signing).

    @DeleteMapping("/appointments/{id}")
    @Transactional
    fun cancelAppointment(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable id: Long
    ): Map<String, String> {
        val profile = currentPatientProfile(principal)
        val appointment = appointments.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found") }

        if (appointment.patient.id != profile.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }
        if (BookingPaymentPolicy.isLocked24h(appointment.startAt, Instant.now())) {
            throw ResponseStatusException(HttpStatus.CONFLICT, BookingPaymentPolicy.LOCKED_24H)
        }
        if (appointment.paymentStatus == Appointment.PaymentStatus.PAID) {
            appointment.refundRequired = true
        } else if (appointment.paymentStatus == Appointment.PaymentStatus.PENDING) {
            paymentService.cancelOpenConsultation(appointment.id)
        }

        appointment.status = Appointment.Status.CANCELLED
        appointment.holdExpiresAt = null
        appointments.save(appointment)

        // Notify doctor that patient cancelled the appointment.
        val doctor = appointment.doctor
        val notif = Notification(
            patient = null,
            doctor = doctor,
            title = "Appointment Cancelled",
            message = "Patient ${appointment.patient.fullName} cancelled their appointment.",
            type = Notification.Type.APPOINTMENT_CANCELLED_BY_PATIENT,
            appointmentId = appointment.id
        )
        val savedNotif = notifications.save(notif)
        fcmService.sendDoctorNotificationAfterCommit(doctor.fcmToken, savedNotif)

        return mapOf("message" to "Appointment cancelled successfully")
    }

    @PostMapping("/appointments")
    @Transactional
    fun bookAppointment(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody req: BookAppointmentRequest
    ): AppointmentDto {
        val profile = currentPatientProfile(principal)
        val patientId = profile.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")

        val doctor = doctorProfiles.findById(req.doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }
        // shortcut: row lock on the doctor, not a range exclusion constraint. Add that after a one-time overlap audit.
        doctorProfiles.findByIdForUpdate(doctor.id!!)

        val startAt = Instant.parse(req.startAt)
        if (!startAt.isAfter(Instant.now())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Time slot is in the past")
        }
        val endAt = startAt.plusSeconds(req.slotMinutes * 60L)

        // Check for overlapping appointments for the doctor
        if (appointments.findOverlapping(doctor.id!!, startAt, endAt).isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Time slot is already booked")
        }

        // Check for overlapping appointments for the patient (prevent double booking)
        val patientOverlapping = appointments.findOverlappingForPatient(patientId, startAt, endAt)
        if (patientOverlapping.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "You already have an appointment scheduled at this date and time. Please choose a different time slot."
            )
        }

        // Resolve structured location early (needed for consecutive-slot validation and pricing).
        //  - Video consultation -> no locationRef.
        //  - Patient provided a locationId -> must belong to this doctor.
        //  - Multi-location doctor -> require a locationId to avoid ambiguity.
        //  - Single-location doctor -> auto-attach the one location.
        //  - Legacy doctor (no structured locations) -> no locationRef; use clinic string.
        val availableLocations = doctorLocations.findByDoctorIdOrderByIsPrimaryDescIdAsc(doctor.id!!)
        val locationRef = when {
            req.isVideo -> null
            req.locationId != null -> availableLocations.firstOrNull { it.id == req.locationId }
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid locationId for this doctor"
                )
            availableLocations.size > 1 -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "This doctor has multiple locations. Please select a location before booking."
            )
            availableLocations.size == 1 -> availableLocations.first()
            else -> null
        }
        val filterSlotsLocation = when {
            req.isVideo -> null
            else -> locationRef?.id
        }
        slotAvailabilityService.assertBookableConsecutiveRange(
            doctor = doctor,
            startAt = startAt,
            endAt = endAt,
            filterLocationId = filterSlotsLocation,
            excludeAppointmentId = null
        )

        val location = when {
            req.isVideo -> "Video Consultation"
            locationRef != null -> locationRef.clinic?.takeIf { it.isNotBlank() } ?: locationRef.label
            else -> doctor.clinic ?: "Clinic"
        }

        val selectedService = req.serviceId?.let {
            doctorServices.findById(it).orElseThrow {
                ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected service not found")
            }
        }
        if (req.isVideo && selectedService == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "serviceId is required for video consultation")
        }
        if (selectedService != null && selectedService.doctor.id != doctor.id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected service does not belong to doctor")
        }
        if (selectedService != null && !selectedService.isActive) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected service is not active")
        }
        if (req.isVideo && selectedService != null && !BookingPaymentPolicy.videoAllowed(selectedService)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Video consultation is not allowed for this service")
        }
        val selectedServicePrice = selectedService?.let { svc ->
            if (svc.isFreeConsultation) {
                null
            } else {
                val priceRows = doctorServicePrices.findByService_IdOrderByCurrencyAsc(svc.id)
                DoctorServicePricing.pickPaymentPrice(priceRows, locationRef?.id)
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected service has no price configured")
            }
        }

        val kind = BookingPaymentPolicy.kind(selectedService)
        val paymentAmountMinor = when (kind) {
            BookingPaymentPolicy.Kind.FREE -> null
            BookingPaymentPolicy.Kind.PAY_LATER, BookingPaymentPolicy.Kind.PAY_NOW -> selectedServicePrice!!.amountMinor
            BookingPaymentPolicy.Kind.LEGACY -> doctor.consultationPriceMinor
        }
        val paymentCurrency = when (kind) {
            BookingPaymentPolicy.Kind.FREE -> null
            BookingPaymentPolicy.Kind.PAY_LATER, BookingPaymentPolicy.Kind.PAY_NOW -> selectedServicePrice!!.currency
            BookingPaymentPolicy.Kind.LEGACY -> doctor.consultationCurrency
        }
        val legacyNeedsPayment = kind == BookingPaymentPolicy.Kind.LEGACY && paymentAmountMinor != null
        val provider = if (kind == BookingPaymentPolicy.Kind.PAY_NOW) {
            try {
                BookingPaymentPolicy.chooseProvider(req.paymentProvider, paymentService.enabledConsultationProviders())
            } catch (ex: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message)
            }
        } else {
            null
        }
        val holdPayNow = kind == BookingPaymentPolicy.Kind.PAY_NOW && provider != null
        if (holdPayNow && !paymentCurrency.equals("UZS", ignoreCase = true)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Online payment is available in UZS only (currency=$paymentCurrency).",
            )
        }

        val appointment = Appointment(
            doctor = doctor,
            patient = profile,
            startAt = startAt,
            endAt = endAt,
            location = location,
            locationRef = locationRef,
            reason = req.reason,
            isVideoConsultation = req.isVideo,
            paymentType = when (kind) {
                BookingPaymentPolicy.Kind.PAY_NOW -> ServicePaymentType.PAY_NOW
                BookingPaymentPolicy.Kind.PAY_LATER -> ServicePaymentType.PAY_LATER
                else -> null
            },
            holdExpiresAt = if (holdPayNow) {
                Instant.now().plusSeconds(paymentProperties.holdMinutes.coerceAtLeast(1) * 60)
            } else {
                null
            },
            status = when {
                holdPayNow -> Appointment.Status.PENDING_PAYMENT
                kind == BookingPaymentPolicy.Kind.PAY_NOW && provider == null -> Appointment.Status.REQUESTED
                kind == BookingPaymentPolicy.Kind.PAY_LATER -> Appointment.Status.CONFIRMED
                kind == BookingPaymentPolicy.Kind.FREE -> Appointment.Status.CONFIRMED
                legacyNeedsPayment -> Appointment.Status.REQUESTED
                else -> Appointment.Status.CONFIRMED
            },
            paymentAmountMinor = paymentAmountMinor,
            paymentCurrency = paymentCurrency,
            serviceId = selectedService?.id,
            serviceTitle = selectedService?.title,
            paymentStatus = when {
                holdPayNow || legacyNeedsPayment ||
                    (kind == BookingPaymentPolicy.Kind.PAY_NOW && provider == null) ->
                    Appointment.PaymentStatus.PENDING
                kind == BookingPaymentPolicy.Kind.PAY_LATER -> Appointment.PaymentStatus.PAY_IN_CLINIC
                else -> Appointment.PaymentStatus.NOT_REQUIRED
            }
        )

        val saved = appointments.save(appointment)

        val documentIds = req.documentIds.orEmpty().filter { it > 0 }.distinct().take(5)
        if (documentIds.isNotEmpty()) {
            patientDocumentService.linkDocumentsToAppointment(patientId, saved, documentIds)
            appointmentBriefingService.enqueueIfNeeded(saved.id)
            val appointmentId = saved.id
            // Generate AI briefing only after the booking transaction commits.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCommit() {
                            appointmentBriefingService.generateAsync(appointmentId)
                        }
                    }
                )
            } else {
                appointmentBriefingService.generateAsync(appointmentId)
            }
        }

        val rescheduledFrom = req.rescheduleAppointmentId?.let { oldId ->
            val old = appointments.findById(oldId).orElse(null)
            if (old == null || old.patient.id != patientId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot reschedule that appointment")
            }
            if (old.status == Appointment.Status.CANCELLED) {
                null
            } else {
                if (BookingPaymentPolicy.isLocked24h(old.startAt, Instant.now())) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, BookingPaymentPolicy.LOCKED_24H)
                }
                if (old.paymentStatus == Appointment.PaymentStatus.PAID) {
                    old.refundRequired = true
                }
                old.status = Appointment.Status.CANCELLED
                old.holdExpiresAt = null
                appointments.save(old)
                old
            }
        }

        var checkout: PaymentService.ConsultationCheckoutResult? = null
        if (saved.status == Appointment.Status.PENDING_PAYMENT && provider != null) {
            appointments.flush()
            checkout = paymentService.createConsultationCheckout(
                userId = principal.user.id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User id missing"),
                appointmentId = saved.id,
                preferredGateway = provider,
            )
        }

        val patientName = profile.fullName ?: "Patient"
        val (notifTitle, notifMessage, notifType) = if (rescheduledFrom != null) {
            val (oldDate, oldTime) = NotificationFormatting.appointmentDateTimeStrings(
                rescheduledFrom.startAt,
                doctor.timeZone,
            )
            val (newDate, newTime) = NotificationFormatting.appointmentDateTimeStrings(
                saved.startAt,
                doctor.timeZone,
            )
            Triple(
                "Appointment Rescheduled",
                "Patient $patientName moved their appointment from $oldDate at $oldTime to $newDate at $newTime.",
                Notification.Type.APPOINTMENT_RESCHEDULED_BY_PATIENT,
            )
        } else {
            Triple(
                "New Appointment Booked",
                NotificationFormatting.patientBookedMessage(
                    patientName = patientName,
                    startAt = saved.startAt,
                    timeZone = doctor.timeZone,
                ),
                Notification.Type.APPOINTMENT_BOOKED_BY_PATIENT,
            )
        }
        if (saved.status != Appointment.Status.PENDING_PAYMENT) {
            val notif = Notification(
                patient = null,
                doctor = doctor,
                title = notifTitle,
                message = notifMessage,
                type = notifType,
                appointmentId = saved.id
            )
            val savedNotif = notifications.save(notif)
            fcmService.sendDoctorNotificationAfterCommit(doctor.fcmToken, savedNotif)
        }

        return toAppointmentDto(
            saved,
            payment = checkout?.let {
                PaymentCheckoutDto(
                    id = it.paymentId,
                    provider = it.gateway,
                    checkoutUrl = it.checkoutUrl,
                    expiresAt = saved.holdExpiresAt?.toString(),
                )
            },
        )
    }

    private fun toAppointmentDto(
        appt: Appointment,
        payment: PaymentCheckoutDto? = null,
    ): AppointmentDto {
        val locked = BookingPaymentPolicy.isLocked24h(appt.startAt, Instant.now())
        val canChange = BookingPaymentPolicy.canPatientChange(appt.status, locked)
        return AppointmentDto(
            id = appt.id,
            doctorId = appt.doctor.id,
            doctorName = "${appt.doctor.firstName} ${appt.doctor.lastName}".trim(),
            doctorProfession = appt.doctor.profession,
            doctorClinic = appt.locationRef?.clinic?.takeIf { it.isNotBlank() } ?: appt.doctor.clinic,
            doctorPhotoUrl = normalizeAvatarUrl(appt.doctor.avatarUrl, appProps.publicBaseUrl),
            startAt = appt.startAt.toString(),
            endAt = appt.endAt.toString(),
            location = appt.location,
            reason = appt.reason,
            status = appt.status.name,
            paymentStatus = appt.paymentStatus.name,
            paymentAmountMinor = appt.paymentAmountMinor,
            paymentCurrency = appt.paymentCurrency,
            locationId = appt.locationRef?.id,
            locationLabel = appt.locationRef?.label,
            locationAddress = appt.locationRef?.address,
            isVideoConsultation = appt.isVideoConsultation,
            paymentType = appt.paymentType?.name,
            paymentBadge = BookingPaymentPolicy.paymentBadge(appt.paymentStatus),
            holdExpiresAt = appt.holdExpiresAt?.toString(),
            serviceId = appt.serviceId,
            serviceTitle = appt.serviceTitle,
            isLocked24h = locked,
            canCancel = canChange,
            canReschedule = canChange,
            refundRequired = appt.refundRequired,
            payment = payment,
        )
    }

    // -------------------- Documents Endpoints (patient's own) --------------------

    @GetMapping("/documents")
    fun getDocuments(
        @AuthenticationPrincipal principal: PatientPrincipal
    ): List<PatientDocumentDto> {
        val profile = currentPatientProfile(principal)
        val patientId = profile.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")
        return patientDocumentService.listForPatientSelf(patientId)
    }

    @PostMapping("/documents")
    fun uploadDocument(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("title", required = false) title: String?,
        @RequestParam("date", required = false) date: String?,
        @RequestParam("isChatAttachment", required = false, defaultValue = "false") isChatAttachment: Boolean,
        @RequestParam("category", required = false) category: String?
    ): PatientDocumentDto {
        val profile = currentPatientProfile(principal)
        val patientId = profile.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")
        val docDate = date?.let { LocalDate.parse(it) }
        return patientDocumentService.uploadByPatient(
            patientId = patientId,
            file = file,
            title = title,
            date = docDate,
            isChatAttachment = isChatAttachment,
            category = category
        )
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDocument(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @PathVariable documentId: Long
    ) {
        val profile = currentPatientProfile(principal)
        val patientId = profile.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")
        patientDocumentService.deleteByPatient(documentId, patientId)
    }
}
