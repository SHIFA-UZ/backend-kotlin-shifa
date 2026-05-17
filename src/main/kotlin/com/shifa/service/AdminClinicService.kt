package com.shifa.service

import com.shifa.domain.Clinic
import com.shifa.domain.ClinicMembership
import com.shifa.domain.DoctorProfile
import com.shifa.domain.Role
import com.shifa.repo.ClinicMembershipRepository
import com.shifa.repo.ClinicRepository
import com.shifa.repo.DoctorProfileRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@Service
class AdminClinicService(
    private val clinics: ClinicRepository,
    private val doctors: DoctorProfileRepository,
    private val memberships: ClinicMembershipRepository,
) {

    data class ClinicRow(
        val id: Long,
        val name: String,
        val phone: String?,
        val email: String?,
        val address: String?,
        val timeZone: String,
        val doctorCount: Int,
        val updatedAt: String,
    )

    data class ClinicDoctorDto(
        val doctorProfileId: Long,
        val userId: Long,
        val displayName: String,
        val membershipRole: String,
    )

    data class ClinicDetail(
        val id: Long,
        val name: String,
        val phone: String?,
        val email: String?,
        val address: String?,
        val timeZone: String,
        val createdAt: String,
        val updatedAt: String,
        val doctors: List<ClinicDoctorDto>,
    )

    fun listPaged(pageable: Pageable): Page<ClinicRow> {
        return clinics.findAll(pageable).map { c ->
            val count = doctors.findAllByPracticeClinic_Id(c.id).size
            ClinicRow(
                id = c.id,
                name = c.name,
                phone = c.phone,
                email = c.email,
                address = c.address,
                timeZone = c.timeZone,
                doctorCount = count,
                updatedAt = c.updatedAt.toString(),
            )
        }
    }

    fun getDetail(clinicId: Long): ClinicDetail {
        val c = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        val members = doctors.findAllByPracticeClinic_Id(clinicId)
            .sortedBy { "${it.firstName} ${it.lastName}" }
            .map { dp ->
                val role = memberships.findByClinic_IdAndUser_Id(clinicId, dp.user.id)
                    ?.takeIf { it.active }?.membershipRole?.name ?: "DOCTOR"
                ClinicDoctorDto(
                    doctorProfileId = dp.id!!,
                    userId = dp.user.id,
                    displayName = "${dp.firstName} ${dp.lastName}".trim(),
                    membershipRole = role,
                )
            }
        return ClinicDetail(
            id = c.id,
            name = c.name,
            phone = c.phone,
            email = c.email,
            address = c.address,
            timeZone = c.timeZone,
            createdAt = c.createdAt.toString(),
            updatedAt = c.updatedAt.toString(),
            doctors = members,
        )
    }

    @Transactional
    fun create(
        name: String,
        phone: String?,
        email: String?,
        address: String?,
        timeZone: String?,
    ): ClinicDetail {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required")
        val tz = timeZone?.trim()?.takeIf { it.isNotEmpty() } ?: "Asia/Tashkent"
        val c = clinics.save(
            Clinic(
                name = trimmed,
                phone = phone?.trim()?.takeIf { it.isNotEmpty() },
                email = email?.trim()?.takeIf { it.isNotEmpty() },
                address = address?.trim()?.takeIf { it.isNotEmpty() },
                timeZone = tz,
            )
        )
        return getDetail(c.id)
    }

    @Transactional
    fun update(
        clinicId: Long,
        name: String,
        phone: String?,
        email: String?,
        address: String?,
        timeZone: String?,
    ): ClinicDetail {
        val c = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required")
        val tz = timeZone?.trim()?.takeIf { it.isNotEmpty() } ?: c.timeZone

        val oldName = c.name
        c.name = trimmedName
        c.phone = phone?.trim()?.takeIf { it.isNotEmpty() }
        c.email = email?.trim()?.takeIf { it.isNotEmpty() }
        c.address = address?.trim()?.takeIf { it.isNotEmpty() }
        c.timeZone = tz
        c.updatedAt = OffsetDateTime.now()
        clinics.save(c)

        if (oldName != trimmedName) {
            for (doc in doctors.findAllByPracticeClinic_Id(clinicId)) {
                doc.clinic = c.name
                doctors.save(doc)
            }
        }
        return getDetail(clinicId)
    }

    /** Assign (or move) doctor to clinic: updates practice FK, memberships, legacy clinic label. */
    @Transactional
    fun assignDoctor(clinicId: Long, doctorProfileId: Long) {
        val clinic = clinics.findById(clinicId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        }
        val doctor = doctors.findById(doctorProfileId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
        }
        if (doctor.user.role != Role.DOCTOR) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "User is not a doctor account")
        }
        deactivateDoctorPracticeMemberships(doctor)

        val othersBefore = doctors.findAllByPracticeClinic_Id(clinicId).count { it.id != doctorProfileId }
        val role =
            if (othersBefore == 0) ClinicMembership.MembershipRole.OWNER else ClinicMembership.MembershipRole.DOCTOR

        var existingPair = memberships.findByClinic_IdAndUser_Id(clinicId, doctor.user.id)
        if (existingPair != null) {
            existingPair.membershipRole = role
            existingPair.doctorProfile = doctor
            existingPair.active = true
            memberships.save(existingPair)
        } else {
            memberships.save(
                ClinicMembership(
                    clinic = clinic,
                    user = doctor.user,
                    membershipRole = role,
                    doctorProfile = doctor,
                    active = true,
                )
            )
        }

        doctor.practiceClinic = clinic
        doctor.clinic = clinic.name
        doctors.save(doctor)
    }

    @Transactional
    fun removeDoctor(clinicId: Long, doctorProfileId: Long) {
        if (!clinics.existsById(clinicId)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Clinic not found")
        val doctor = doctors.findById(doctorProfileId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
        }
        val current = doctor.practiceClinic?.id
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Doctor is not assigned to any clinic")
        if (current != clinicId) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Doctor is not in this clinic")
        memberships.findByClinic_IdAndUser_Id(clinicId, doctor.user.id)?.let {
            it.active = false
            memberships.save(it)
        }
        doctor.practiceClinic = null
        doctor.clinic = null
        doctors.save(doctor)
    }

    private fun deactivateDoctorPracticeMemberships(doctor: DoctorProfile) {
        memberships.findByDoctorProfile_IdAndActiveTrue(doctor.id!!).forEach { m ->
            m.active = false
            memberships.save(m)
        }
    }
}
