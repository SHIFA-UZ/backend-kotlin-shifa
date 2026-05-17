package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.security.ClinicStaffPrincipal
import com.shifa.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class ClinicAccessService(
    private val doctors: DoctorProfileRepository,
    private val appointments: AppointmentRepository,
    private val patients: PatientProfileRepository
) {

    fun resolveActorDoctorProfile(principal: Any): DoctorProfile? =
        (principal as? DoctorPrincipal)?.profile

    fun assertPracticeActor(principal: Any) {
        when (principal) {
            is DoctorPrincipal, is ClinicStaffPrincipal -> Unit
            else -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    fun doctorIdsForPatientDirectory(principal: Any): List<Long> {
        assertPracticeActor(principal)
        return when (principal) {
            is DoctorPrincipal -> {
                val cid = principal.profile.practiceClinic?.id
                if (cid != null) doctors.findAllByPracticeClinic_Id(cid).map { it.id }
                else listOf(principal.profile.id)
            }
            is ClinicStaffPrincipal -> {
                val ids = linkedSetOf<Long>()
                for (clinicId in principal.clinicIds()) {
                    ids.addAll(doctors.findAllByPracticeClinic_Id(clinicId).map { it.id })
                }
                ids.toList()
            }
            else -> emptyList()
        }
    }

    fun assertCanViewDoctorCalendar(principal: Any, targetDoctorId: Long) {
        assertPracticeActor(principal)
        if (!canViewDoctorCalendar(principal, targetDoctorId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot access this doctor's calendar")
        }
    }

    fun canViewDoctorCalendar(principal: Any, targetDoctorId: Long): Boolean {
        val target = doctors.findById(targetDoctorId).orElse(null) ?: return false
        if (principal is DoctorPrincipal && principal.profile.id == targetDoctorId) return true
        val clinicId = target.practiceClinic?.id
            ?: return principal is DoctorPrincipal && principal.profile.id == targetDoctorId
        return when (principal) {
            is DoctorPrincipal -> principal.profile.practiceClinic?.id == clinicId
            is ClinicStaffPrincipal -> principal.clinicIds().contains(clinicId)
            else -> false
        }
    }

    fun assertCanAccessAppointmentResource(principal: Any, appointmentDoctorId: Long) {
        assertCanViewDoctorCalendar(principal, appointmentDoctorId)
    }

    fun assertPatientVisible(principal: Any, patientId: Long) {
        val doctorIds = doctorIdsForPatientDirectory(principal).toSet()
        if (doctorIds.isEmpty()) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val p = patients.findById(patientId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
        val createdBy = p.createdByDoctor?.id
        if (createdBy != null && doctorIds.contains(createdBy)) return
        val ok = doctorIds.any { appointments.existsByDoctorIdAndPatientId(it, patientId) }
        if (!ok) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found")
    }

    fun resolveBookingActorUserId(principal: Any): Long =
        when (principal) {
            is DoctorPrincipal -> principal.profile.user.id
            is ClinicStaffPrincipal -> principal.user.id
            else -> throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }

    /** Assign created-by doctor when clinic staff registers a patient (first doctor in clinic). */
    fun resolveDoctorForPatientCreation(principal: Any): DoctorProfile {
        resolveActorDoctorProfile(principal)?.let { return it }
        val staff = principal as? ClinicStaffPrincipal
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val clinicId = staff.clinicIds().firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff has no clinic")
        return doctors.findAllByPracticeClinic_Id(clinicId).minByOrNull { it.id!! }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No doctor in clinic")
    }

    fun assertPrincipalMayAccessClinic(principal: Any, clinicId: Long) {
        assertPracticeActor(principal)
        when (principal) {
            is DoctorPrincipal -> {
                val cid = principal.profile.practiceClinic?.id
                    ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Doctor is not assigned to a clinic")
                if (cid != clinicId) throw ResponseStatusException(HttpStatus.FORBIDDEN)
            }
            is ClinicStaffPrincipal -> {
                if (!principal.clinicIds().contains(clinicId)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
            }
            else -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }
}
