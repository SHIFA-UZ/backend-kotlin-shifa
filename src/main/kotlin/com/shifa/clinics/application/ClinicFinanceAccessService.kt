package com.shifa.clinics.application

import com.shifa.clinics.domain.ClinicMembership.MembershipRole
import com.shifa.clinics.infrastructure.ClinicMembershipRepository
import com.shifa.shared.security.ClinicStaffPrincipal
import com.shifa.shared.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.transaction.annotation.Transactional

@Service
class ClinicFinanceAccessService(
    private val memberships: ClinicMembershipRepository,
    private val clinicAccess: ClinicAccessService,
    private val patients: com.shifa.patients.application.PatientProfiles,
) {

    enum class FinanceAccessLevel {
        FULL,
        RECORD_PAYMENTS,
        VIEW_OWN_PATIENTS,
        NONE
    }

    @Transactional(readOnly = true)
    fun resolveFinanceAccessLevel(principal: Any, clinicId: Long): FinanceAccessLevel {
        val role = resolveMembershipRole(principal, clinicId) ?: return FinanceAccessLevel.NONE
        return when (role) {
            MembershipRole.OWNER, MembershipRole.CLINIC_ADMIN -> FinanceAccessLevel.FULL
            MembershipRole.RECEPTIONIST -> FinanceAccessLevel.RECORD_PAYMENTS
            MembershipRole.DOCTOR, MembershipRole.NURSE -> FinanceAccessLevel.VIEW_OWN_PATIENTS
            else -> FinanceAccessLevel.NONE
        }
    }

    @Transactional(readOnly = true)
    fun assertCanViewFinance(principal: Any, clinicId: Long) {
        val level = resolveFinanceAccessLevel(principal, clinicId)
        if (level == FinanceAccessLevel.NONE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No finance access")
        }
    }

    /**
     * Payments, installment creation, and non-void invoice updates for a specific patient.
     * Clinic admins / receptionists: clinic-wide. Doctors / nurses: only visible patients.
     */
    @Transactional(readOnly = true)
    fun assertCanManagePatientFinance(principal: Any, clinicId: Long, patientId: Long) {
        assertCanViewFinance(principal, clinicId)
        when (resolveFinanceAccessLevel(principal, clinicId)) {
            FinanceAccessLevel.FULL, FinanceAccessLevel.RECORD_PAYMENTS -> return
            FinanceAccessLevel.VIEW_OWN_PATIENTS -> clinicAccess.assertPatientVisible(principal, patientId)
            FinanceAccessLevel.NONE -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    /**
     * null = full clinic visibility (admin/receptionist); non-null = restrict to these patient IDs.
     */
    @Transactional(readOnly = true)
    fun financeReadPatientIdFilter(principal: Any, clinicId: Long): Set<Long>? {
        assertCanViewFinance(principal, clinicId)
        return when (resolveFinanceAccessLevel(principal, clinicId)) {
            FinanceAccessLevel.FULL, FinanceAccessLevel.RECORD_PAYMENTS -> null
            FinanceAccessLevel.VIEW_OWN_PATIENTS -> {
                val doctorIds = clinicAccess.doctorIdsForPatientDirectory(principal)
                patients.findVisiblePatientIdsLinkedToDoctors(doctorIds).toSet()
            }
            FinanceAccessLevel.NONE -> throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    @Transactional(readOnly = true)
    fun assertCanVoidRecords(principal: Any, clinicId: Long) {
        val level = resolveFinanceAccessLevel(principal, clinicId)
        if (level != FinanceAccessLevel.FULL) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot void records")
        }
    }

    @Transactional(readOnly = true)
    fun assertCanManageFinanceSettings(principal: Any, clinicId: Long) {
        val role = resolveMembershipRole(principal, clinicId)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "No clinic membership")
        if (role != MembershipRole.OWNER && role != MembershipRole.CLINIC_ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot manage finance settings")
        }
    }

    private fun resolveMembershipRole(principal: Any, clinicId: Long): MembershipRole? {
        when (principal) {
            is DoctorPrincipal -> {
                val userId = principal.profile.user.id
                val membership = memberships.findByUserIdAndClinicIdAndActiveTrue(userId, clinicId)
                if (membership != null) {
                    return membership.membershipRole
                }
                // Legacy fallback when practice clinic is set but membership row is missing.
                val practiceId = principal.profile.practiceClinic?.id
                if (practiceId != null && practiceId == clinicId) {
                    return MembershipRole.DOCTOR
                }
                return null
            }
            is ClinicStaffPrincipal -> {
                val userId = principal.user.id
                val membership = memberships.findByUserIdAndClinicIdAndActiveTrue(userId, clinicId)
                    ?: return null
                return membership.membershipRole
            }
            else -> return null
        }
    }
}
