package com.shifa.service

import com.shifa.domain.Appointment
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
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.ZoneId
import java.time.ZonedDateTime

@Service
class ClinicWorkspaceService(
    private val clinicAccess: ClinicAccessService,
    private val financeAccess: ClinicFinanceAccessService,
    private val memberships: ClinicMembershipRepository,
    private val clinics: ClinicRepository,
    private val doctors: DoctorProfileRepository,
    private val appointments: AppointmentRepository,
    private val patients: PatientProfileRepository,
    private val adminClinicService: AdminClinicService,
    private val revenueShareService: DoctorRevenueShareService,
) {

    data class MyClinicSummary(
        val clinicId: Long,
        val name: String,
        val timeZone: String,
        val currency: String,
        val phone: String?,
        val email: String?,
        val address: String?,
        val membershipRole: String,
        val isPracticeClinic: Boolean,
        val defaultDoctorRevenueSharePercent: Int? = null,
        val canManageFinanceSettings: Boolean = false,
    )

    data class ClinicFinanceSettingsDto(
        val defaultDoctorRevenueSharePercent: Int?,
    )

    data class MemberRevenueShareDto(
        val doctorProfileId: Long,
        val doctorRevenueSharePercent: Int?,
        val effectiveRevenueSharePercent: Int?,
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

    data class ClinicPatientAppointmentRow(
        val id: Long,
        val startAt: String,
        val endAt: String,
        val status: String,
        val doctorProfileId: Long,
        val doctorName: String,
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
            currency = c.currency.trim().uppercase().ifEmpty { "UZS" },
            phone = c.phone,
            email = c.email,
            address = c.address,
            membershipRole = membershipRole,
            isPracticeClinic = isPracticeClinic,
            defaultDoctorRevenueSharePercent = c.defaultDoctorRevenueSharePercent,
            canManageFinanceSettings = membershipRole == ClinicMembership.MembershipRole.OWNER.name ||
                membershipRole == ClinicMembership.MembershipRole.CLINIC_ADMIN.name,
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

    @Transactional
    fun updateFinanceSettings(
        principal: Any,
        clinicId: Long,
        defaultDoctorRevenueSharePercent: Int?,
    ): ClinicFinanceSettingsDto {
        financeAccess.assertCanManageFinanceSettings(principal, clinicId)
        revenueShareService.validatePercent(defaultDoctorRevenueSharePercent)
        val clinic = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        clinic.defaultDoctorRevenueSharePercent = defaultDoctorRevenueSharePercent
        clinics.save(clinic)
        return ClinicFinanceSettingsDto(defaultDoctorRevenueSharePercent = clinic.defaultDoctorRevenueSharePercent)
    }

    @Transactional
    fun updateMemberRevenueShare(
        principal: Any,
        clinicId: Long,
        doctorProfileId: Long,
        doctorRevenueSharePercent: Int?,
    ): MemberRevenueShareDto {
        financeAccess.assertCanManageFinanceSettings(principal, clinicId)
        revenueShareService.validatePercent(doctorRevenueSharePercent)
        val doctor = doctors.findById(doctorProfileId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
        }
        if (doctor.practiceClinic?.id != clinicId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Doctor is not assigned to this clinic")
        }
        val membership = memberships.findByClinic_IdAndUser_Id(clinicId, doctor.user.id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic membership not found")
        if (!membership.active) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Membership is inactive")
        }
        revenueShareService.assertMembershipMayHaveRevenueShare(membership)
        membership.doctorRevenueSharePercent = doctorRevenueSharePercent
        memberships.save(membership)
        val clinic = clinics.findById(clinicId).orElseThrow()
        val effective = revenueShareService.resolveEffectivePercent(
            membership.doctorRevenueSharePercent,
            clinic.defaultDoctorRevenueSharePercent,
        )
        return MemberRevenueShareDto(
            doctorProfileId = doctorProfileId,
            doctorRevenueSharePercent = membership.doctorRevenueSharePercent,
            effectiveRevenueSharePercent = effective,
        )
    }

    fun listPatients(principal: Any, clinicId: Long, q: String?, pageable: Pageable): Page<ClinicPatientRow> {
        clinicAccess.assertPrincipalMayAccessClinic(principal, clinicId)
        val clinicDoctorProfiles = doctors.findAllByPracticeClinic_Id(clinicId)
        val clinicDoctorIds = clinicDoctorProfiles.mapNotNull { it.id }

        val trimmedQ = q?.trim()?.takeIf { it.isNotEmpty() }

        val page: Page<PatientProfile> =
            if (clinicDoctorIds.isEmpty()) {
                Page.empty(pageable)
            } else {
                patients.findClinicRosterForDoctors(clinicDoctorIds, trimmedQ, pageable)
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

    fun listPatientAppointments(principal: Any, clinicId: Long, patientId: Long): List<ClinicPatientAppointmentRow> {
        clinicAccess.assertPatientLinkedToClinic(principal, patientId, clinicId)
        val doctorIds = doctors.findAllByPracticeClinic_Id(clinicId).mapNotNull { it.id }.toSet()
        if (doctorIds.isEmpty()) return emptyList()
        return appointments.findByPatientId(patientId)
            .asSequence()
            .filter { it.doctor.id != null && doctorIds.contains(it.doctor.id) && it.status != Appointment.Status.CANCELLED }
            .sortedByDescending { it.startAt }
            .map {
                ClinicPatientAppointmentRow(
                    id = it.id,
                    startAt = it.startAt.toString(),
                    endAt = it.endAt.toString(),
                    status = it.status.name,
                    doctorProfileId = it.doctor.id!!,
                    doctorName = "${it.doctor.firstName} ${it.doctor.lastName}",
                )
            }
            .toList()
    }
}
