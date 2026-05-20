package com.shifa.service

import com.shifa.domain.ClinicMembership.MembershipRole
import com.shifa.repo.ClinicMembershipRepository
import com.shifa.security.ClinicStaffPrincipal
import com.shifa.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class ClinicFinanceAccessService(
    private val memberships: ClinicMembershipRepository,
) {

    enum class FinanceAccessLevel {
        FULL,
        RECORD_PAYMENTS,
        VIEW_OWN_PATIENTS,
        NONE
    }

    fun resolveFinanceAccessLevel(principal: Any, clinicId: Long): FinanceAccessLevel {
        val role = resolveMembershipRole(principal, clinicId) ?: return FinanceAccessLevel.NONE
        return when (role) {
            MembershipRole.OWNER, MembershipRole.CLINIC_ADMIN -> FinanceAccessLevel.FULL
            MembershipRole.RECEPTIONIST -> FinanceAccessLevel.RECORD_PAYMENTS
            MembershipRole.DOCTOR, MembershipRole.NURSE -> FinanceAccessLevel.VIEW_OWN_PATIENTS
            else -> FinanceAccessLevel.NONE
        }
    }

    fun assertCanViewFinance(principal: Any, clinicId: Long) {
        val level = resolveFinanceAccessLevel(principal, clinicId)
        if (level == FinanceAccessLevel.NONE) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No finance access")
        }
    }

    fun assertCanRecordPayment(principal: Any, clinicId: Long) {
        val level = resolveFinanceAccessLevel(principal, clinicId)
        if (level == FinanceAccessLevel.NONE || level == FinanceAccessLevel.VIEW_OWN_PATIENTS) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot record payments")
        }
    }

    fun assertCanManageInvoices(principal: Any, clinicId: Long) {
        val level = resolveFinanceAccessLevel(principal, clinicId)
        if (level != FinanceAccessLevel.FULL) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot manage invoices")
        }
    }

    fun assertCanManageInstallments(principal: Any, clinicId: Long) {
        val level = resolveFinanceAccessLevel(principal, clinicId)
        if (level != FinanceAccessLevel.FULL) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot manage installments")
        }
    }

    fun assertCanVoidRecords(principal: Any, clinicId: Long) {
        val level = resolveFinanceAccessLevel(principal, clinicId)
        if (level != FinanceAccessLevel.FULL) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot void records")
        }
    }

    private fun resolveMembershipRole(principal: Any, clinicId: Long): MembershipRole? {
        val userId = when (principal) {
            is DoctorPrincipal -> principal.profile.user.id
            is ClinicStaffPrincipal -> principal.user.id
            else -> return null
        }
        val membership = memberships.findByUserIdAndClinicIdAndActiveTrue(userId, clinicId)
            ?: return null
        return membership.membershipRole
    }
}
