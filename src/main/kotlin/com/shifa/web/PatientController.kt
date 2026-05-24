// src/main/kotlin/com/shifa/web/PatientController.kt
package com.shifa.web

import com.shifa.config.AppProperties
import java.time.ZoneOffset
import com.shifa.domain.Appointment
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
import com.shifa.service.DoctorServicePricing
import com.shifa.service.FcmService
import com.shifa.service.PatientDocumentService
import com.shifa.service.SlotAvailabilityService
import com.shifa.service.PatientProfileMapper
import com.shifa.web.dto.PatientDocumentDto
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
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
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
    private val subscriptionTierService: com.shifa.service.SubscriptionTierService,
    private val slotAvailabilityService: SlotAvailabilityService
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
        val locationAddress: String? = null
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
        val locationId: Long? = null
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

        return filtered.map { appt ->
            AppointmentDto(
                id = appt.id,
                doctorId = appt.doctor.id,
                doctorName = "${appt.doctor.firstName} ${appt.doctor.lastName}".trim(),
                doctorProfession = appt.doctor.profession,
                doctorClinic = appt.doctor.clinic,
                doctorPhotoUrl = normalizeAvatarUrl(appt.doctor.avatarUrl, appProps.publicBaseUrl),
                startAt = appt.startAt.toString(),
                endAt = appt.endAt.toString(),
                location = appt.location,
                reason = appt.reason,
                status = appt.status.name,
                locationId = appt.locationRef?.id,
                locationLabel = appt.locationRef?.label,
                locationAddress = appt.locationRef?.address
            )
        }
    }

    // GET /api/patients/me/appointments/{id} is handled by PatientAppointmentController.getById
    // (returns AppointmentSummaryDto with signatureRequested, alreadySigned for details + signing).

    @DeleteMapping("/appointments/{id}")
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

        appointment.status = Appointment.Status.CANCELLED
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
        doctor.fcmToken?.let { fcmService.sendDoctorNotification(it, savedNotif) }

        return mapOf("message" to "Appointment cancelled successfully")
    }

    @PostMapping("/appointments")
    fun bookAppointment(
        @AuthenticationPrincipal principal: PatientPrincipal,
        @RequestBody req: BookAppointmentRequest
    ): AppointmentDto {
        val profile = currentPatientProfile(principal)
        val patientId = profile.id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient ID not found")

        val doctor = doctorProfiles.findById(req.doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }

        val startAt = Instant.parse(req.startAt)
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
        val selectedServicePrice = selectedService?.let { svc ->
            if (svc.isFreeConsultation) {
                null
            } else {
                val priceRows = doctorServicePrices.findByService_IdOrderByCurrencyAsc(svc.id)
                DoctorServicePricing.pickPaymentPrice(priceRows, locationRef?.id)
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected service has no price configured")
            }
        }

        val paymentAmountMinor = when {
            selectedService?.isFreeConsultation == true -> null
            selectedServicePrice != null -> selectedServicePrice.amountMinor
            else -> doctor.consultationPriceMinor
        }
        val paymentCurrency = when {
            selectedService?.isFreeConsultation == true -> null
            selectedServicePrice != null -> selectedServicePrice.currency
            else -> doctor.consultationCurrency
        }
        val needsPayment = paymentAmountMinor != null

        val appointment = Appointment(
            doctor = doctor,
            patient = profile,
            startAt = startAt,
            endAt = endAt,
            location = location,
            locationRef = locationRef,
            reason = req.reason,
            // Business rule:
            // - On-site (no payment required): immediately confirmed.
            // - Paid video: requested until payment webhook marks it paid+confirmed.
            status = if (needsPayment) {
                Appointment.Status.REQUESTED
            } else {
                Appointment.Status.CONFIRMED
            },
            paymentAmountMinor = paymentAmountMinor,
            paymentCurrency = paymentCurrency,
            serviceId = selectedService?.id,
            serviceTitle = selectedService?.title,
            paymentStatus = if (needsPayment) {
                Appointment.PaymentStatus.PENDING
            } else {
                Appointment.PaymentStatus.NOT_REQUIRED
            }
        )

        val saved = appointments.save(appointment)

        // Notify doctor that patient booked a new appointment.
        val notif = Notification(
            patient = null,
            doctor = doctor,
            title = "New Appointment Booked",
            message = "Patient ${profile.fullName} booked an appointment.",
            type = Notification.Type.APPOINTMENT_BOOKED_BY_PATIENT,
            appointmentId = saved.id
        )
        val savedNotif = notifications.save(notif)
        doctor.fcmToken?.let { fcmService.sendDoctorNotification(it, savedNotif) }

        return AppointmentDto(
            id = saved.id,
            doctorId = saved.doctor.id,
            doctorName = "${saved.doctor.firstName} ${saved.doctor.lastName}".trim(),
            doctorProfession = saved.doctor.profession,
            doctorClinic = saved.doctor.clinic,
            doctorPhotoUrl = normalizeAvatarUrl(saved.doctor.avatarUrl, appProps.publicBaseUrl),
            startAt = saved.startAt.toString(),
            endAt = saved.endAt.toString(),
            location = saved.location,
            reason = saved.reason,
            status = saved.status.name,
            paymentStatus = saved.paymentStatus.name,
            paymentAmountMinor = saved.paymentAmountMinor,
            paymentCurrency = saved.paymentCurrency,
            locationId = saved.locationRef?.id,
            locationLabel = saved.locationRef?.label,
            locationAddress = saved.locationRef?.address
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
