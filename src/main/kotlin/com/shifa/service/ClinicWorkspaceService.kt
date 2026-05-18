package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.ClinicMembership
import com.shifa.domain.PatientProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ClinicMembershipRepository
import com.shifa.repo.ClinicRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.ClinicStaffPrincipal
import com.shifa.security.DoctorPrincipal
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.ZoneId
import java.time.ZonedDateTime

@Service
class ClinicWorkspaceService(
    private val clinicAccess: ClinicAccessService,
    private val memberships: ClinicMembershipRepository,
    private val clinics: ClinicRepository,
    private val doctors: DoctorProfileRepository,
    private val appointments: AppointmentRepository,
    private val patients: PatientProfileRepository,
    private val adminClinicService: AdminClinicService,
) {

    data class MyClinicSummary(
        val clinicId: Long,
        val name: String,
        val timeZone: String,
        val phone: String?,
        val email: String?,
        val address: String?,
        val membershipRole: String,
        val isPracticeClinic: Boolean,
    )

    data class OverviewStats(
        val appointmentsToday: Long,
        val activeDoctors: Int,
        val patientsThisMonth: Long,
        val averageWaitingMinutes: Int? = null,
        val occupancyPercent: Int? = null,
    )

    data class ClinicPatientRow(
        val patientId: Long,
        val fullName: String,
        val phone: String?,
        val email: String?,
    )

    fun listMyClinics(principal: Any): List<MyClinicSummary> {
        clinicAccess.assertPracticeActor(principal)
        return when (principal) {
            is DoctorPrincipal -> myClinicsForDoctor(principal)
            is ClinicStaffPrincipal -> myClinicsForStaff(principal)
            else -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    private fun myClinicsForStaff(p: ClinicStaffPrincipal): List<MyClinicSummary> {
        return p.memberships
            .filter { it.active }
            .map { m ->
                toSummary(
                    m.clinic,
                    m.membershipRole.name,
                    isPracticeClinic = false,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun myClinicsForDoctor(p: DoctorPrincipal): List<MyClinicSummary> {
        val userId = p.profile.user.id
        val practiceId = p.profile.practiceClinic?.id
        val rows = memberships.findByUserIdAndActiveTrue(userId)
        val seenClinicIds = linkedSetOf<Long>()
        val out = mutableListOf<MyClinicSummary>()

        for (m in rows) {
            seenClinicIds.add(m.clinic.id)
            out.add(
                toSummary(
                    m.clinic,
                    m.membershipRole.name,
                    isPracticeClinic = practiceId != null && practiceId == m.clinic.id,
                )
            )
        }
        if (practiceId != null && practiceId !in seenClinicIds) {
            val c = clinics.findById(practiceId).orElse(null)
            if (c != null) {
                out.add(
                    toSummary(
                        c,
                        ClinicMembership.MembershipRole.DOCTOR.name,
                        isPracticeClinic = true,
                    )
                )
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun toSummary(c: Clinic, membershipRole: String, isPracticeClinic: Boolean) =
        MyClinicSummary(
            clinicId = c.id,
            name = c.name,
            timeZone = c.timeZone,
            phone = c.phone,
            email = c.email,
            address = c.address,
            membershipRole = membershipRole,
            isPracticeClinic = isPracticeClinic,
        )

    fun getOverviewStats(principal: Any, clinicId: Long): OverviewStats {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val c = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        val clinicDoctorIds = doctors.findAllByPracticeClinic_Id(clinicId).mapNotNull { it.id }
        val zone = ZoneId.of(c.timeZone)
        val now = ZonedDateTime.now(zone)
        val dayStart = now.toLocalDate().atStartOfDay(zone).toInstant()
        val dayEnd = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()

        val monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant()
        val monthEnd = now.toLocalDate().plusMonths(1).withDayOfMonth(1).atStartOfDay(zone).toInstant()

        val todayCount =
            if (clinicDoctorIds.isEmpty()) 0L
            else appointments.countByDoctorIdsAndStartAtBetween(clinicDoctorIds, dayStart, dayEnd)
        val monthPatients =
            if (clinicDoctorIds.isEmpty()) 0L
            else appointments.countDistinctPatientsByDoctorIdsAndStartAtBetween(clinicDoctorIds, monthStart, monthEnd)

        return OverviewStats(
            appointmentsToday = todayCount,
            activeDoctors = clinicDoctorIds.size,
            patientsThisMonth = monthPatients,
            averageWaitingMinutes = null,
            occupancyPercent = null,
        )
    }

    fun listMembers(principal: Any, clinicId: Long): List<AdminClinicService.ClinicDoctorDto> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        return adminClinicService.getDetail(clinicId).doctors
    }

    fun listPatients(principal: Any, clinicId: Long, q: String?, pageable: Pageable): Page<ClinicPatientRow> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val role = resolveWorkspaceRole(principal, clinicId)
        val clinicDoctorProfiles = doctors.findAllByPracticeClinic_Id(clinicId)
        val clinicDoctorIds = clinicDoctorProfiles.mapNotNull { it.id }

        val trimmedQ = q?.trim()?.takeIf { it.isNotEmpty() }

        val page: Page<PatientProfile> =
            if (shouldUseFullClinicPatientRoster(role, principal)) {
                if (clinicDoctorIds.isEmpty()) {
                    Page.empty(pageable)
                } else {
                    patients.findClinicRosterForDoctors(clinicDoctorIds, trimmedQ, pageable)
                }
            } else {
                val actorDoctorId = clinicAccess.resolveActorDoctorProfile(principal)?.id
                    ?: throw ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "This view requires a doctor profile (try another role or account).",
                    )
                patients.findClinicRosterScopedToDoctor(actorDoctorId, trimmedQ, pageable)
            }

        return page.map { p ->
            ClinicPatientRow(
                patientId = p.id ?: 0L,
                fullName = p.fullName,
                phone = p.phone,
                email = p.email,
            )
        }
    }

    /**
     * Workspace visibility for patient roster.
     * Staff in operational roles see the full clinic visitor list.
     * Doctors (and doctor-role memberships) see patients they treated or added.
     */
    private fun shouldUseFullClinicPatientRoster(role: ClinicMembership.MembershipRole?, principal: Any): Boolean {
        if (role == null) return false
        if (principal is ClinicStaffPrincipal) {
            return when (role) {
                ClinicMembership.MembershipRole.OWNER,
                ClinicMembership.MembershipRole.CLINIC_ADMIN,
                ClinicMembership.MembershipRole.RECEPTIONIST,
                ClinicMembership.MembershipRole.STAFF,
                ClinicMembership.MembershipRole.NURSE,
                -> true
                ClinicMembership.MembershipRole.DOCTOR -> false
            }
        }
        if (principal !is DoctorPrincipal) return false
        return when (role) {
            ClinicMembership.MembershipRole.OWNER,
            ClinicMembership.MembershipRole.CLINIC_ADMIN,
            ClinicMembership.MembershipRole.RECEPTIONIST,
            ClinicMembership.MembershipRole.STAFF,
            -> true
            ClinicMembership.MembershipRole.DOCTOR,
            ClinicMembership.MembershipRole.NURSE,
            -> false
        }
    }

    private fun resolveWorkspaceRole(principal: Any, clinicId: Long): ClinicMembership.MembershipRole? {
        return when (principal) {
            is DoctorPrincipal -> {
                val explicit = memberships.findByUserIdAndClinicIdAndActiveTrue(
                    principal.profile.user.id,
                    clinicId,
                )
                if (explicit != null) return explicit.membershipRole
                val cid = principal.profile.practiceClinic?.id
                if (cid != null && cid == clinicId) {
                    ClinicMembership.MembershipRole.DOCTOR
                } else {
                    null
                }
            }
            is ClinicStaffPrincipal -> {
                principal.memberships
                    .firstOrNull { it.active && it.clinic.id == clinicId }
                    ?.membershipRole
            }
            else -> null
        }
    }
}
