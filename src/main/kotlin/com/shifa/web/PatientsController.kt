// src/main/kotlin/com/shifa/web/PatientsController.kt
package com.shifa.web

import com.shifa.domain.PatientDocument
import com.shifa.domain.PatientProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DocumentAccessGrantRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.PatientProphylaxisSettingRepository
import com.shifa.repo.RemoteCareTaskRepository
import com.shifa.service.ClinicAccessService
import com.shifa.i18n.SmsReminderFormatting
import com.shifa.service.DevSmsService
import com.shifa.service.DoctorSmsBillingService
import com.shifa.service.PatientAccountService
import com.shifa.service.PatientProfileMapper
import com.shifa.security.DoctorPrincipal
import com.shifa.util.PhoneNormalizer
import com.shifa.util.PatientPhones
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.slf4j.LoggerFactory
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.Hibernate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import com.shifa.domain.Appointment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RestController
@RequestMapping("/api/patients")
class PatientsController(
    private val patientsRepo: PatientProfileRepository,
    private val appointmentRepo: AppointmentRepository,
    private val accessGrants: DocumentAccessGrantRepository,
    private val profileMapper: PatientProfileMapper,
    private val patientAccountService: PatientAccountService,
    private val clinicAccess: ClinicAccessService,
    private val doctorProfiles: DoctorProfileRepository,
    private val doctorSmsBilling: DoctorSmsBillingService,
    private val devSmsService: DevSmsService,
    private val prophylaxisSettingsRepo: PatientProphylaxisSettingRepository,
    private val remoteCareTaskRepo: RemoteCareTaskRepository,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(PatientsController::class.java)
    }

    // -------------------- DTOs --------------------

    data class PatientDto(
        val id: Long?,
        val name: String,
        val phone: String?,
        val phones: List<String> = emptyList(),
        val email: String?,
        val address: String?, // Legacy field - will be populated from structured location if available
        val birthDate: String?,
        val language: String?,
        val photoUrl: String?,              // absolute URL
        val chronicDisease: String?,
        val hasAccount: Boolean,            // ✅ NEW
        val username: String?,              // ✅ NEW
        val documents: List<DocumentDto>,
        // Structured location fields
        val locationCountry: String? = null,
        val locationRegion: String? = null,
        val locationDistrict: String? = null,
        val locationCity: String? = null,
        val locationPostalCode: String? = null,
        val locationStreetAddress: String? = null,
        /** IANA timezone for remote task schedule (e.g. Europe/Berlin). Doctor enters times in this zone. */
        val timeZone: String? = null,
        /** DevSMS before each future appointment when enabled. */
        val smsReminderEnabled: Boolean = false,
        /** Hours before appointment to send SMS (1 or 24). */
        val smsReminderHoursBefore: Int = 24,
        val gender: String? = null,
        val bloodGroup: String? = null,
        val allergies: String? = null,
        /** ACTIVE | AT_RISK | FOLLOW_UP — computed for roster display. */
        val clinicalStatus: String = "ACTIVE",
        val atRisk: Boolean = false,
        val followUpRequired: Boolean = false,
    )

    data class DocumentDto(
        val id: Long?,
        val title: String,
        val date: String,
        val url: String?,
        val canView: Boolean = true,
        val creatorLabel: String = "Unknown"
    )

    /** Minimal DTO for calendar assign-patient: search by id or name, show avatar. */
    data class PatientAssignmentDto(val id: Long?, val name: String, val photoUrl: String? = null)

    // SECURITY (NEW): Bean validation to reject invalid input before business logic; prevents oversized/XSS payloads
    data class CreatePatientRequest(
        @field:NotBlank(message = "Name is required")
        @field:Size(min = 1, max = 255)
        val name: String,
        @field:Size(max = 50)
        val phone: String?,
        val phones: List<@Size(max = 50) String>? = null,
        @field:Email @field:Size(max = 255)
        val email: String?,
        @field:Size(max = 500)
        val address: String?,
        @field:Size(max = 10)
        val birthDate: String?,
        @field:Size(max = 20)
        val language: String?,
        @field:Size(max = 2048)
        val photoUrl: String?,
        @field:Size(max = 1000)
        val chronicDisease: String?,
        @field:Size(max = 20)
        val gender: String? = null,
        @field:Size(max = 10)
        val bloodGroup: String? = null,
        @field:Size(max = 1000)
        val allergies: String? = null,
    )

    data class UpdatePatientRequest(
        @field:Size(max = 255)
        val name: String?,
        @field:Size(max = 50)
        val phone: String?,
        val phones: List<@Size(max = 50) String>? = null,
        @field:Email @field:Size(max = 255)
        val email: String?,
        @field:Size(max = 500)
        val address: String?,
        @field:Size(max = 10)
        val birthDate: String?,
        @field:Size(max = 20)
        val language: String?,
        @field:Size(max = 2048)
        val photoUrl: String?,
        @field:Size(max = 1000)
        val chronicDisease: String?,
        val smsReminderEnabled: Boolean? = null,
        val smsReminderHoursBefore: Int? = null,
        @field:Size(max = 20)
        val gender: String? = null,
        @field:Size(max = 10)
        val bloodGroup: String? = null,
        @field:Size(max = 1000)
        val allergies: String? = null,
    )

    private fun resolvePhoneInputs(phone: String?, phones: List<String>?): List<String> {
        val fromList = phones.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        if (fromList.isNotEmpty()) return fromList
        val single = phone?.trim()?.takeIf { it.isNotEmpty() }
        return if (single != null) listOf(single) else emptyList()
    }

    private fun assertPhonesAvailable(rawPhones: List<String>, excludePatientId: Long? = null) {
        val seen = mutableSetOf<String>()
        rawPhones.forEach { raw ->
            val normalized = PhoneNormalizer.normalize(raw)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone number: $raw")
            if (!seen.add(normalized)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate phone number in request.")
            }
            val existing = patientsRepo.findByPhoneNormalized(normalized).orElse(null)
            if (existing != null && existing.id != excludePatientId) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Patient with this phone number already exists.")
            }
        }
    }

    private fun applyPhonesToPatient(patient: PatientProfile, rawPhones: List<String>) {
        if (rawPhones.isEmpty()) {
            patient.phone = null
            patient.phoneNormalized = null
            patient.additionalPhones = null
            return
        }
        val normalizedPhones = rawPhones.map { raw ->
            PhoneNormalizer.normalize(raw) ?: raw.trim()
        }
        patient.phone = normalizedPhones.first()
        patient.phoneNormalized = PhoneNormalizer.normalize(normalizedPhones.first())
        patient.additionalPhones = PatientPhones.serializeAdditional(normalizedPhones.drop(1))
    }

    // -------------------- Endpoints --------------------

    /**
     * GET /api/patients
     * "My patients" directory for a logged-in doctor: patients this doctor saw (non-cancelled appointments)
     * or created. Clinic-wide combined roster is on GET /api/clinics/{clinicId}/patients (workspace).
     * Clinic staff still get the union of patients tied to any doctor in their assigned clinics.
     * Never returns 500: patients without user accounts or with broken relations are returned with minimal DTOs.
     */
    @GetMapping
    @Transactional(readOnly = true)
    fun getAllPatients(
        @AuthenticationPrincipal principal: Any,
        @PageableDefault(size = 50) pageable: Pageable
    ): List<PatientDto> {
        return try {
            val vid = viewerDoctorId(principal)
            val patients =
                when (principal) {
                    is DoctorPrincipal ->
                        patientsRepo.findClinicRosterScopedToDoctor(principal.profile.id, null, pageable).content
                    else -> {
                        val doctorIds = clinicAccess.doctorIdsForPatientDirectory(principal)
                        if (doctorIds.isEmpty()) return emptyList()
                        patientsRepo.findClinicRosterForDoctors(doctorIds, null, pageable).content
                    }
                }
            val clinicalContext = buildClinicalContext(patients.mapNotNull { it.id })
            patients.map { toDto(it, vid, clinicalContextFor(it.id, clinicalContext)) }
        } catch (e: Exception) {
            logger.error("Failed to load patient list for doctor: {}", e.message, e)
            emptyList()
        }
    }

    /**
     * GET /api/patients/for-assignment
     * Full patient profile directory for calendar booking: every stored patient (paginated), including first-time /
     * never-seen-here profiles. Narrow "my patients" for charts lives on GET /api/patients instead.
     */
    @GetMapping("/for-assignment")
    @Transactional(readOnly = true)
    fun getPatientsForAssignment(
        @AuthenticationPrincipal principal: Any,
        @PageableDefault(size = 500, sort = ["fullName"], direction = Sort.Direction.ASC) pageable: Pageable
    ): Page<PatientAssignmentDto> {
        clinicAccess.assertPracticeActor(principal)
        val sort = if (pageable.sort.isSorted) pageable.sort else Sort.by(Sort.Direction.ASC, "fullName")
        val patients = patientsRepo.findAll(PageRequest.of(pageable.pageNumber, pageable.pageSize, sort))
        return patients.map { p ->
            PatientAssignmentDto(
                id = p.id,
                name = p.fullName,
                photoUrl = profileMapper.normalizePhotoUrl(p.photoUrl)
            )
        }
    }

    /**
     * GET /api/patients/{id}
     * Optional [clinicId]: when set, patient must be linked to that clinic roster (workspace). Otherwise "my patients" directory rules apply.
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun getPatient(
        @PathVariable id: Long,
        @AuthenticationPrincipal principal: Any,
        @RequestParam(required = false) clinicId: Long?,
    ): PatientDto {
        clinicAccess.assertPracticeActor(principal)
        if (clinicId != null) {
            clinicAccess.assertPatientLinkedToClinic(principal, id, clinicId)
        } else {
            clinicAccess.assertPatientVisible(principal, id)
        }
        val p = patientsRepo.findById(id)
            .orElseThrow { IllegalArgumentException("Patient not found") }
        return toDto(p, viewerDoctorId(principal), clinicalContextFor(p.id, buildClinicalContext(listOfNotNull(p.id))))
    }

    data class PatientAppointmentRow(
        val id: Long,
        val startAt: String,
        val endAt: String,
        val status: String,
        val location: String?,
        val isVideo: Boolean,
        val reason: String?,
    )

    /**
     * GET /api/patients/{id}/appointments
     * Appointment history for this patient with the logged-in doctor (non-cancelled).
     */
    @GetMapping("/{id}/appointments")
    @Transactional(readOnly = true)
    fun getPatientAppointments(
        @PathVariable id: Long,
        @AuthenticationPrincipal principal: Any,
    ): List<PatientAppointmentRow> {
        clinicAccess.assertPracticeActor(principal)
        clinicAccess.assertPatientVisible(principal, id)
        val doctorId = viewerDoctorId(principal)
        return appointmentRepo.findByPatientIdAndDoctorIdOrderByStartAtDesc(id, doctorId)
            .map {
                PatientAppointmentRow(
                    id = it.id,
                    startAt = it.startAt.toString(),
                    endAt = it.endAt.toString(),
                    status = it.status.name,
                    location = it.location,
                    isVideo = it.location.contains("video", ignoreCase = true),
                    reason = it.reason,
                )
            }
    }

    /**
     * POST /api/patients
     * Creates a new patient.
     */
    @PostMapping
    fun createPatient(
        @AuthenticationPrincipal principal: Any,
        @RequestBody @Valid req: CreatePatientRequest
    ): PatientDto {
        val rawPhones = resolvePhoneInputs(req.phone, req.phones)
        assertPhonesAvailable(rawPhones)

        val patient = PatientProfile(
            fullName = req.name.trim(),
            email = req.email?.trim(),
            address = req.address?.trim(),
            birthDate = req.birthDate?.let { LocalDate.parse(it) },
            language = req.language?.trim(),
            photoUrl = req.photoUrl?.trim(),
            chronicDisease = req.chronicDisease?.trim(),
            gender = req.gender?.trim(),
            bloodGroup = req.bloodGroup?.trim(),
            allergies = req.allergies?.trim(),
            documents = mutableListOf<PatientDocument>()
        )
        applyPhonesToPatient(patient, rawPhones)
        patient.createdByDoctor = clinicAccess.resolveDoctorForPatientCreation(principal)

        val saved = patientsRepo.save(patient)
        return toDto(saved, viewerDoctorId(principal), clinicalContextFor(saved.id, buildClinicalContext(listOfNotNull(saved.id))))
    }

    /**
     * POST /api/patients/{id}/create-account
     * Generates a patient user account.
     */
    @PostMapping("/{id}/create-account")
    fun createAccount(
        @PathVariable id: Long,
        @AuthenticationPrincipal principal: Any
    ): PatientAccountService.AccountCreationResult {
        val p = patientsRepo.findById(id).orElseThrow { IllegalArgumentException("Patient not found") }
        clinicAccess.assertPracticeActor(principal)
        clinicAccess.assertPatientVisible(principal, id)
        return patientAccountService.createPatientAccount(id)
    }

    private fun viewerDoctorId(principal: Any): Long =
        clinicAccess.resolveActorDoctorProfile(principal)?.id
            ?: clinicAccess.doctorIdsForPatientDirectory(principal).minOrNull()
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot resolve viewer doctor")

    /**
     * PATCH /api/patients/{id}
     * Updates a patient's information.
     */
    @PatchMapping("/{id}")
    fun updatePatient(
        @PathVariable id: Long,
        @AuthenticationPrincipal principal: Any,
        @RequestBody @Valid req: UpdatePatientRequest
    ): PatientDto {
        val patient = patientsRepo.findById(id)
            .orElseThrow { IllegalArgumentException("Patient not found") }
        clinicAccess.assertPracticeActor(principal)
        clinicAccess.assertPatientVisible(principal, id)
        req.name?.let { patient.fullName = it.trim() }
        if (req.phones != null || req.phone != null) {
            val rawPhones = resolvePhoneInputs(req.phone, req.phones)
            assertPhonesAvailable(rawPhones, excludePatientId = patient.id)
            applyPhonesToPatient(patient, rawPhones)
        }
        req.email?.let { patient.email = it.trim() }
        req.address?.let { patient.address = it.trim() }
        req.birthDate?.let { patient.birthDate = LocalDate.parse(it) }
        req.language?.let { patient.language = it.trim() }
        req.photoUrl?.let { patient.photoUrl = it.trim() }
        req.chronicDisease?.let { patient.chronicDisease = it.trim().takeIf { it.isNotEmpty() } }
        req.gender?.let { patient.gender = it.trim().takeIf { it.isNotEmpty() } }
        req.bloodGroup?.let { patient.bloodGroup = it.trim().takeIf { it.isNotEmpty() } }
        req.allergies?.let { patient.allergies = it.trim().takeIf { it.isNotEmpty() } }
        req.smsReminderEnabled?.let { enabled ->
            if (enabled) {
                val doctorId = viewerDoctorId(principal)
                val doctor = doctorProfiles.findById(doctorId).orElseThrow {
                    ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
                }
                if (!doctorSmsBilling.isSmsAllowed(doctor)) {
                    throw ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "SMS appointment reminders are not enabled for your account. Contact support.",
                    )
                }
            }
            patient.smsReminderEnabled = enabled
        }
        req.smsReminderHoursBefore?.let { hours ->
            if (hours !in setOf(1, 24)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "smsReminderHoursBefore must be 1 or 24",
                )
            }
            patient.smsReminderHoursBefore = hours
        }

        val saved = patientsRepo.save(patient)
        return toDto(saved, viewerDoctorId(principal), clinicalContextFor(saved.id, buildClinicalContext(listOfNotNull(saved.id))))
    }

    /**
     * POST /api/patients/{id}/send-test-sms
     * Sends one SMS immediately (DevSMS) for testing; billed like a real reminder.
     */
    @PostMapping("/{id}/send-test-sms")
    fun sendTestSms(
        @PathVariable id: Long,
        @AuthenticationPrincipal principal: Any,
    ): Map<String, Any?> {
        clinicAccess.assertPracticeActor(principal)
        clinicAccess.assertPatientVisible(principal, id)
        val patient = patientsRepo.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found") }
        val doctorId = viewerDoctorId(principal)
        val doctor = doctorProfiles.findById(doctorId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
        }
        if (!doctorSmsBilling.isSmsAllowed(doctor)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "SMS appointment reminders are not enabled for your account. Contact support.",
            )
        }
        if (!devSmsService.isConfigured()) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SMS service is not configured (DEVSMS_API_TOKEN missing).",
            )
        }
        val phone = patient.phoneNormalized ?: patient.phone
        if (phone.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient has no phone number")
        }
        val doctorName = "${doctor.firstName} ${doctor.lastName}".trim()
        val zone = doctor.timeZone?.takeIf { it.isNotBlank() } ?: "UTC"
        val sampleStart = appointmentRepo
            .findFirstByPatient_IdAndDoctor_IdAndStartAtAfterAndStatusNotOrderByStartAtAsc(
                id,
                doctorId,
                Instant.now(),
                Appointment.Status.CANCELLED,
            )?.startAt
            ?: run {
                val zoneId = runCatching { ZoneId.of(zone) }.getOrElse { ZoneId.of("UTC") }
                ZonedDateTime.now(zoneId)
                    .plusDays(1)
                    .withHour(10)
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0)
                    .toInstant()
            }
        val message = SmsReminderFormatting.testReminderBody(
            patientName = patient.fullName,
            doctorName = doctorName,
            startAt = sampleStart,
            timeZone = zone,
        )
        val result = devSmsService.sendSms(phone, message)
        if (!result.success) {
            val detail = result.errorMessage?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Failed to send SMS via DevSMS. Check token, balance, and phone format."
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, detail)
        }
        doctorSmsBilling.recordSentSms(
            doctor = doctor,
            patient = patient,
            appointmentId = null,
            devsmsSmsId = result.smsId,
        )
        return mapOf(
            "success" to true,
            "smsId" to result.smsId,
            "costMinor" to doctorSmsBilling.pricePerSmsMinor,
            "currency" to doctorSmsBilling.currency,
        )
    }

    private data class ClinicalContext(
        val prophylaxisPatientIds: Set<Long>,
        val activeTaskPatientIds: Set<Long>,
    )

    private fun buildClinicalContext(patientIds: List<Long>): ClinicalContext {
        if (patientIds.isEmpty()) {
            return ClinicalContext(emptySet(), emptySet())
        }
        val prophylaxis = prophylaxisSettingsRepo.findEnabledPatientIdsIn(patientIds).toSet()
        val activeTasks = remoteCareTaskRepo.findActiveTaskPatientIdsIn(patientIds).toSet()
        return ClinicalContext(prophylaxis, activeTasks)
    }

    private fun clinicalContextFor(patientId: Long?, context: ClinicalContext): ClinicalContext =
        if (patientId == null) ClinicalContext(emptySet(), emptySet()) else context

    private fun resolveClinicalFlags(
        p: PatientProfile,
        context: ClinicalContext,
    ): Triple<String, Boolean, Boolean> {
        val chronic = p.chronicDisease?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
        val atRisk = chronic != null
        val followUpRequired = p.id != null && (
            p.id!! in context.prophylaxisPatientIds ||
                p.id!! in context.activeTaskPatientIds
            )
        val status = when {
            atRisk -> "AT_RISK"
            followUpRequired -> "FOLLOW_UP"
            else -> "ACTIVE"
        }
        return Triple(status, atRisk, followUpRequired)
    }

    private fun toDto(
        p: PatientProfile,
        doctorId: Long,
        context: ClinicalContext = ClinicalContext(emptySet(), emptySet()),
    ): PatientDto {
        return try {
            toDtoInternal(p, doctorId, context)
        } catch (e: Exception) {
            logger.warn("Failed to map patient to DTO (patientId={}, doctorId={}): {} - returning minimal DTO", p.id, doctorId, e.message, e)
            toDtoMinimal(p, context)
        }
    }

    /** Minimal DTO when full mapping fails (e.g. patient has no user account or broken relations). Never throws. */
    private fun toDtoMinimal(p: PatientProfile, context: ClinicalContext = ClinicalContext(emptySet(), emptySet())): PatientDto {
        val allPhones = PatientPhones.allPhones(p.phone, p.additionalPhones)
        val (clinicalStatus, atRisk, followUpRequired) = resolveClinicalFlags(p, context)
        return PatientDto(
            id = p.id,
            name = p.fullName,
            phone = p.phone,
            phones = allPhones,
            email = p.email,
            address = p.address,
            birthDate = p.birthDate?.toString(),
            language = p.language,
            photoUrl = try { profileMapper.normalizePhotoUrl(p.photoUrl) } catch (_: Exception) { null },
            chronicDisease = p.chronicDisease,
            hasAccount = false,
            username = null,
            documents = emptyList(),
            locationCountry = p.locationCountry,
            locationRegion = p.locationRegion,
            locationDistrict = p.locationDistrict,
            locationCity = p.locationCity,
            locationPostalCode = p.locationPostalCode,
            locationStreetAddress = p.locationStreetAddress,
            timeZone = p.timeZone,
            smsReminderEnabled = p.smsReminderEnabled,
            smsReminderHoursBefore = p.smsReminderHoursBefore,
            gender = p.gender,
            bloodGroup = p.bloodGroup,
            allergies = p.allergies,
            clinicalStatus = clinicalStatus,
            atRisk = atRisk,
            followUpRequired = followUpRequired,
        )
    }

    private fun toDtoInternal(
        p: PatientProfile,
        doctorId: Long,
        context: ClinicalContext,
    ): PatientDto {
        // Force Hibernate to load the documents collection while the session is open
        Hibernate.initialize(p.documents)

        // Safe user fields: patient may have no linked user account (user_id null or orphaned)
        val (hasAccount, username) = safeUserFields(p)

        // Build legacy address from structured location if address is empty
        val legacyAddress = p.address?.takeIf { it.isNotBlank() }
            ?: buildString {
                if (p.locationStreetAddress?.isNotBlank() == true) {
                    append(p.locationStreetAddress)
                }
                if (p.locationCity?.isNotBlank() == true) {
                    if (isNotEmpty()) append(", ")
                    append(p.locationCity)
                }
                if (p.locationDistrict?.isNotBlank() == true) {
                    if (isNotEmpty()) append(", ")
                    append(p.locationDistrict)
                }
                if (p.locationRegion?.isNotBlank() == true) {
                    if (isNotEmpty()) append(", ")
                    append(p.locationRegion)
                }
            }.takeIf { it.isNotBlank() }

        val docDtos = try {
            p.documents
                .filter { it.filePath.isNotBlank() && !it.isChatAttachment }
                .map { d -> documentToDto(d, doctorId) }
        } catch (_: Exception) {
            emptyList()
        }

        val (clinicalStatus, atRisk, followUpRequired) = resolveClinicalFlags(p, context)

        return PatientDto(
            id = p.id,
            name = p.fullName,
            phone = p.phone,
            phones = PatientPhones.allPhones(p.phone, p.additionalPhones),
            email = p.email,
            address = legacyAddress,
            birthDate = p.birthDate?.toString(),
            language = p.language,
            photoUrl = profileMapper.normalizePhotoUrl(p.photoUrl),
            chronicDisease = p.chronicDisease,
            hasAccount = hasAccount,
            username = username,
            documents = docDtos,
            locationCountry = p.locationCountry,
            locationRegion = p.locationRegion,
            locationDistrict = p.locationDistrict,
            locationCity = p.locationCity,
            locationPostalCode = p.locationPostalCode,
            locationStreetAddress = p.locationStreetAddress,
            timeZone = p.timeZone,
            smsReminderEnabled = p.smsReminderEnabled,
            smsReminderHoursBefore = p.smsReminderHoursBefore,
            gender = p.gender,
            bloodGroup = p.bloodGroup,
            allergies = p.allergies,
            clinicalStatus = clinicalStatus,
            atRisk = atRisk,
            followUpRequired = followUpRequired,
        )
    }

    /**
     * Safely read user-related fields from a patient. Never throws.
     * Returns (hasAccount, username); if user is null or access fails, returns (false, null).
     */
    private fun safeUserFields(p: PatientProfile): Pair<Boolean, String?> {
        return try {
            val u = p.user
            (u != null) to (u?.username)
        } catch (_: Exception) {
            false to null
        }
    }

    private fun documentToDto(d: PatientDocument, doctorId: Long): DocumentDto {
        return try {
            val canView = d.uploadedByDoctor?.id == doctorId ||
                (d.id != null && accessGrants.existsByDocument_IdAndDoctor_Id(d.id!!, doctorId))
            val creatorLabel = when {
                d.uploadedByDoctor != null -> {
                    val doctor = d.uploadedByDoctor!!
                    val profileName = listOf(doctor.firstName, doctor.lastName)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                    if (profileName.isNotBlank()) profileName
                    else {
                        val username = doctor.user.username?.trim().orEmpty()
                        val email = doctor.user.email?.trim().orEmpty()
                        val phone = doctor.user.phone?.trim().orEmpty()
                        when {
                            username.isNotEmpty() -> username
                            email.isNotEmpty() -> email
                            phone.isNotEmpty() -> phone
                            else -> "Doctor"
                        }
                    }
                }
                d.uploadedByPatientProfile != null -> d.uploadedByPatientProfile!!.fullName.trim().ifBlank { "Patient" }
                else -> "Unknown"
            }
            val url = if (canView && d.filePath.isNotBlank()) profileMapper.normalizePhotoUrl(d.filePath) else null
            DocumentDto(
                id = d.id,
                title = d.title,
                date = d.date.toString(),
                url = url,
                canView = canView,
                creatorLabel = creatorLabel
            )
        } catch (_: Exception) {
            DocumentDto(id = d.id, title = d.title, date = d.date.toString(), url = null, canView = false, creatorLabel = "Unknown")
        }
    }
}
