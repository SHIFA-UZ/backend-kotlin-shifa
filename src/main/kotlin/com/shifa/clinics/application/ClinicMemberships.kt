package com.shifa.clinics.application

import com.shifa.clinics.domain.ClinicMembership
import com.shifa.clinics.infrastructure.ClinicMembershipRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to ClinicMembership records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [ClinicMembershipRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class ClinicMemberships(private val repo: ClinicMembershipRepository) {
    fun findByUserIdAndActiveTrue(userId: Long): List<ClinicMembership> = repo.findByUserIdAndActiveTrue(userId)
    fun findByClinicIdAndActiveTrue(clinicId: Long): List<ClinicMembership> = repo.findByClinicIdAndActiveTrue(clinicId)
    fun findByDoctorProfile_IdAndActiveTrue(doctorProfileId: Long): List<ClinicMembership> = repo.findByDoctorProfile_IdAndActiveTrue(doctorProfileId)
    fun findByClinic_IdAndUser_Id(clinicId: Long, userId: Long): ClinicMembership? = repo.findByClinic_IdAndUser_Id(clinicId, userId)
    fun save(entity: ClinicMembership): ClinicMembership = repo.save(entity)
}
