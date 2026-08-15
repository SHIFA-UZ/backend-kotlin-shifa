package com.shifa.service

import com.shifa.domain.CarePartnership
import com.shifa.domain.CarePartnershipProgressUpdate
import com.shifa.domain.Notification
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.CarePartnershipProgressUpdateRepository
import com.shifa.repo.CarePartnershipRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientProfileRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

data class CarePartnerSearchHit(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val profession: String?,
    val clinic: String?,
    val locationCountry: String?,
    val locationCity: String?,
    val avatarUrl: String?,
)

data class CarePartnershipDto(
    val id: Long,
    val status: String,
    val patientId: Long,
    val patientName: String?,
    val initiatingDoctorId: Long,
    val initiatingDoctorName: String,
    val partnerDoctorId: Long,
    val partnerDoctorName: String,
    val specialtyRequested: String?,
    val message: String?,
    val originatingAppointmentId: Long?,
    val createdAt: String,
    val updatedAt: String,
    /** INITIATOR | PARTNER — role of the requesting doctor for this partnership. */
    val viewerRole: String? = null,
)

data class CarePartnershipProgressDto(
    val id: Long,
    val authorDoctorId: Long,
    val authorDoctorName: String,
    val body: String,
    val createdAt: String,
)

@Service
class CarePartnershipService(
    private val partnerships: CarePartnershipRepository,
    private val progressRepo: CarePartnershipProgressUpdateRepository,
    private val doctors: DoctorProfileRepository,
    private val patients: PatientProfileRepository,
    private val appointments: AppointmentRepository,
    private val access: DoctorPatientAccessService,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
) {

    fun searchPartners(
        actorDoctorId: Long,
        country: String?,
        profession: String?,
        q: String?,
        limit: Int = 30,
    ): List<CarePartnerSearchHit> {
        val resultLimit = limit.coerceIn(1, 40)
        // Cap DB fetch well above resultLimit so country filtering still has room,
        // but never pull the full doctor table.
        val candidateCap = (resultLimit * 8).coerceIn(40, 160)
        val candidates = doctors.findPartnerSearchCandidates(
            excludeDoctorId = actorDoctorId,
            search = q?.trim()?.takeIf { it.isNotEmpty() },
            profession = profession?.trim()?.takeIf { it.isNotEmpty() },
            pageable = org.springframework.data.domain.PageRequest.of(0, candidateCap),
        )
        val countryFilter = country?.takeIf { it.isNotBlank() } ?: "Uzbekistan"
        return candidates
            .asSequence()
            .filter { matchesCountryFilterCheap(it, countryFilter) }
            .take(resultLimit)
            .map { d ->
                CarePartnerSearchHit(
                    id = d.id!!,
                    firstName = d.firstName,
                    lastName = d.lastName,
                    profession = d.profession,
                    clinic = d.clinic,
                    locationCountry = d.locationCountry,
                    locationCity = d.locationCity,
                    avatarUrl = d.avatarUrl,
                )
            }
            .toList()
    }

    @Transactional
    fun invite(
        initiatingDoctorId: Long,
        patientId: Long,
        partnerDoctorId: Long,
        specialtyRequested: String?,
        message: String?,
        originatingAppointmentId: Long?,
    ): CarePartnershipDto {
        if (initiatingDoctorId == partnerDoctorId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot invite yourself")
        }
        if (!access.doctorMayAccessPatient(initiatingDoctorId, patientId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No access to this patient")
        }
        val patient = patients.findById(patientId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found") }
        val initiator = doctors.findById(initiatingDoctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }
        val partner = doctors.findById(partnerDoctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Partner doctor not found") }
        if (partnerships.existsPendingInvite(initiatingDoctorId, partnerDoctorId, patientId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Invite already pending")
        }
        if (partnerships.existsActiveForDoctorAndPatient(partnerDoctorId, patientId) &&
            partnerships.existsActiveForDoctorAndPatient(initiatingDoctorId, patientId)
        ) {
            // Already co-treating — allow re-invite only if not both already active together;
            // still block duplicate active pair by checking pending above.
        }
        val appt = originatingAppointmentId?.let {
            appointments.findById(it).orElse(null)
        }
        val row = CarePartnership(
            patient = patient,
            initiatingDoctor = initiator,
            partnerDoctor = partner,
            status = CarePartnership.Status.PENDING,
            originatingAppointment = appt,
            specialtyRequested = specialtyRequested?.takeIf { it.isNotBlank() },
            message = message?.takeIf { it.isNotBlank() },
        )
        val saved = partnerships.save(row)
        notifyDoctor(
            doctor = partner,
            title = "Therapy partner invite",
            message = "${doctorName(initiator)} invited you to co-treat ${patient.fullName ?: "a patient"}.",
            type = Notification.Type.CARE_PARTNERSHIP_INVITE,
            extraData = mapOf("partnershipId" to saved.id.toString()),
        )
        return toDto(saved, initiatingDoctorId)
    }

    @Transactional
    fun accept(partnershipId: Long, actorDoctorId: Long): CarePartnershipDto {
        val row = getOrThrow(partnershipId)
        if (row.partnerDoctor.id != actorDoctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the invited partner can accept")
        }
        if (row.status != CarePartnership.Status.PENDING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite is not pending")
        }
        row.status = CarePartnership.Status.ACTIVE
        row.updatedAt = Instant.now()
        val saved = partnerships.save(row)
        notifyDoctor(
            doctor = row.initiatingDoctor,
            title = "Partner accepted",
            message = "${doctorName(row.partnerDoctor)} accepted your therapy partnership.",
            type = Notification.Type.CARE_PARTNERSHIP_ACCEPTED,
            extraData = mapOf("partnershipId" to saved.id.toString()),
        )
        return toDto(saved, actorDoctorId)
    }

    @Transactional
    fun decline(partnershipId: Long, actorDoctorId: Long): CarePartnershipDto {
        val row = getOrThrow(partnershipId)
        if (row.partnerDoctor.id != actorDoctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the invited partner can decline")
        }
        if (row.status != CarePartnership.Status.PENDING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite is not pending")
        }
        row.status = CarePartnership.Status.DECLINED
        row.updatedAt = Instant.now()
        return toDto(partnerships.save(row), actorDoctorId)
    }

    @Transactional
    fun complete(partnershipId: Long, actorDoctorId: Long): CarePartnershipDto {
        val row = getOrThrow(partnershipId)
        assertParticipant(row, actorDoctorId)
        if (row.status != CarePartnership.Status.ACTIVE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Partnership is not active")
        }
        row.status = CarePartnership.Status.COMPLETED
        row.updatedAt = Instant.now()
        return toDto(partnerships.save(row), actorDoctorId)
    }

    @Transactional
    fun cancel(partnershipId: Long, actorDoctorId: Long): CarePartnershipDto {
        val row = getOrThrow(partnershipId)
        if (row.initiatingDoctor.id != actorDoctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the initiator can cancel")
        }
        if (row.status != CarePartnership.Status.PENDING && row.status != CarePartnership.Status.ACTIVE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot cancel this partnership")
        }
        row.status = CarePartnership.Status.CANCELLED
        row.updatedAt = Instant.now()
        return toDto(partnerships.save(row), actorDoctorId)
    }

    @Transactional(readOnly = true)
    fun listMine(doctorId: Long): List<CarePartnershipDto> =
        partnerships.findForDoctor(doctorId).map { toDto(it, doctorId) }

    @Transactional(readOnly = true)
    fun get(partnershipId: Long, actorDoctorId: Long): CarePartnershipDto {
        val row = getOrThrow(partnershipId)
        assertParticipant(row, actorDoctorId)
        return toDto(row, actorDoctorId)
    }

    @Transactional
    fun addProgress(partnershipId: Long, actorDoctorId: Long, body: String): CarePartnershipProgressDto {
        val row = getOrThrow(partnershipId)
        assertParticipant(row, actorDoctorId)
        if (row.status != CarePartnership.Status.ACTIVE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Partnership must be active")
        }
        val text = body.trim()
        if (text.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Progress body required")
        }
        val author = doctors.findById(actorDoctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }
        val update = CarePartnershipProgressUpdate(
            partnership = row,
            authorDoctor = author,
            body = text,
        )
        val saved = progressRepo.save(update)
        row.updatedAt = Instant.now()
        partnerships.save(row)
        val other = if (row.initiatingDoctor.id == actorDoctorId) row.partnerDoctor else row.initiatingDoctor
        notifyDoctor(
            doctor = other,
            title = "Partnership progress update",
            message = "${doctorName(author)} posted a progress update for ${row.patient.fullName ?: "patient"}.",
            type = Notification.Type.CARE_PARTNERSHIP_PROGRESS,
            extraData = mapOf("partnershipId" to row.id.toString()),
        )
        return CarePartnershipProgressDto(
            id = saved.id,
            authorDoctorId = author.id!!,
            authorDoctorName = doctorName(author),
            body = saved.body,
            createdAt = saved.createdAt.toString(),
        )
    }

    @Transactional(readOnly = true)
    fun listProgress(partnershipId: Long, actorDoctorId: Long): List<CarePartnershipProgressDto> {
        val row = getOrThrow(partnershipId)
        assertParticipant(row, actorDoctorId)
        return progressRepo.findByPartnershipIdOrderByCreatedAtAsc(partnershipId).map { u ->
            CarePartnershipProgressDto(
                id = u.id,
                authorDoctorId = u.authorDoctor.id!!,
                authorDoctorName = doctorName(u.authorDoctor),
                body = u.body,
                createdAt = u.createdAt.toString(),
            )
        }
    }

    private fun getOrThrow(id: Long): CarePartnership =
        partnerships.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Partnership not found") }

    private fun assertParticipant(row: CarePartnership, doctorId: Long) {
        if (row.initiatingDoctor.id != doctorId && row.partnerDoctor.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant")
        }
    }

    private fun toDto(row: CarePartnership, viewerDoctorId: Long? = null): CarePartnershipDto {
        val viewerRole = when (viewerDoctorId) {
            row.initiatingDoctor.id -> "INITIATOR"
            row.partnerDoctor.id -> "PARTNER"
            else -> null
        }
        return CarePartnershipDto(
            id = row.id,
            status = row.status.name,
            patientId = row.patient.id!!,
            patientName = row.patient.fullName,
            initiatingDoctorId = row.initiatingDoctor.id!!,
            initiatingDoctorName = doctorName(row.initiatingDoctor),
            partnerDoctorId = row.partnerDoctor.id!!,
            partnerDoctorName = doctorName(row.partnerDoctor),
            specialtyRequested = row.specialtyRequested,
            message = row.message,
            originatingAppointmentId = row.originatingAppointment?.id,
            createdAt = row.createdAt.toString(),
            updatedAt = row.updatedAt.toString(),
            viewerRole = viewerRole,
        )
    }

    private fun doctorName(d: com.shifa.domain.DoctorProfile): String =
        "${d.firstName} ${d.lastName}".trim()

    private fun notifyDoctor(
        doctor: com.shifa.domain.DoctorProfile,
        title: String,
        message: String,
        type: Notification.Type,
        extraData: Map<String, String> = emptyMap(),
    ) {
        val notif = Notification(
            patient = null,
            doctor = doctor,
            title = title,
            message = message,
            type = type,
        )
        val saved = notifications.save(notif)
        fcmService.sendDoctorNotificationAfterCommit(doctor.fcmToken, saved, extraData)
    }

    /**
     * Country match for partner search. Uses profile fields only — no per-row
     * location-table lookups (those caused N+1 CPU/DB spikes on large catalogs).
     */
    private fun matchesCountryFilterCheap(doc: com.shifa.domain.DoctorProfile, country: String): Boolean {
        val filter = country.trim().lowercase()
        if (filter.isEmpty()) return true

        fun fieldMatches(value: String?, keys: Set<String>): Boolean {
            if (value.isNullOrBlank()) return false
            val v = value.trim().lowercase()
            return keys.any { k -> v == k || v.contains(k) }
        }

        val germanyKeys = setOf("germany", "deutschland", "de")
        val uzbekistanKeys = setOf("uzbekistan", "o'zbekiston", "ozbekiston", "uz")
        val germanCities = setOf(
            "berlin", "munich", "münchen", "hamburg", "cologne", "köln",
            "frankfurt", "stuttgart", "düsseldorf", "dortmund", "essen",
            "leipzig", "bremen", "dresden", "hannover", "nuremberg", "nürnberg"
        )

        if (filter == "germany" || filter == "deutschland" || filter == "de") {
            return fieldMatches(doc.locationCountry, germanyKeys) ||
                fieldMatches(doc.locationCity, germanCities)
        }

        if (uzbekistanKeys.contains(filter) || filter.contains("uzbek")) {
            if (fieldMatches(doc.locationCountry, uzbekistanKeys)) return true
            val explicitForeign = fieldMatches(doc.locationCountry, germanyKeys) ||
                fieldMatches(doc.locationCity, germanCities)
            // Legacy blank country ≈ Uzbekistan (same rule as public search).
            return !explicitForeign && doc.locationCountry.isNullOrBlank()
        }

        return fieldMatches(doc.locationCountry, setOf(filter)) ||
            fieldMatches(doc.locationCity, setOf(filter))
    }

    private fun matchesCountryFilter(doc: com.shifa.domain.DoctorProfile, country: String): Boolean =
        matchesCountryFilterCheap(doc, country)
}
