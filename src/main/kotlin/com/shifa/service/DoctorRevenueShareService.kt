package com.shifa.service

import com.shifa.domain.ClinicMembership
import com.shifa.repo.ClinicMembershipRepository
import com.shifa.repo.ClinicRepository
import com.shifa.repo.DoctorProfileRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class DoctorRevenueShareService(
    private val clinics: ClinicRepository,
    private val memberships: ClinicMembershipRepository,
    private val doctors: DoctorProfileRepository,
) {

    fun validatePercent(percent: Int?) {
        if (percent == null) return
        if (percent !in 0..100) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Revenue share percent must be between 0 and 100",
            )
        }
    }

    fun resolveEffectivePercent(
        membershipOverride: Int?,
        clinicDefault: Int?,
    ): Int? = membershipOverride ?: clinicDefault

    /**
     * Effective doctor share % keyed by doctor profile id for active clinic doctors.
     * Doctors without a configured override or clinic default are omitted.
     */
    fun loadEffectiveShareByDoctorProfileId(clinicId: Long): Map<Long, Int> {
        val clinic = clinics.findById(clinicId).orElse(null) ?: return emptyMap()
        val default = clinic.defaultDoctorRevenueSharePercent
        val map = linkedMapOf<Long, Int>()
        for (dp in doctors.findAllByPracticeClinic_Id(clinicId)) {
            val doctorId = dp.id ?: continue
            val membership = memberships.findByClinic_IdAndUser_Id(clinicId, dp.user.id)
                ?.takeIf { it.active }
            val effective = resolveEffectivePercent(
                membership?.doctorRevenueSharePercent,
                default,
            ) ?: continue
            map[doctorId] = effective
        }
        return map
    }

    fun applySplit(agg: ClinicFinanceLedgerService.DoctorEarningAgg, percent: Int): ClinicFinanceLedgerService.DoctorEarningAgg {
        val (doctorGross, clinicGross) = splitAmount(agg.grossMinor, percent)
        val (doctorCollected, clinicCollected) = splitAmount(agg.collectedMinor, percent)
        return agg.copy(
            revenueSharePercent = percent,
            doctorShareGrossMinor = doctorGross,
            clinicShareGrossMinor = clinicGross,
            doctorShareCollectedMinor = doctorCollected,
            clinicShareCollectedMinor = clinicCollected,
        )
    }

    fun applySplits(
        earnings: List<ClinicFinanceLedgerService.DoctorEarningAgg>,
        shareByDoctor: Map<Long, Int>,
    ): List<ClinicFinanceLedgerService.DoctorEarningAgg> =
        earnings.map { agg ->
            val percent = shareByDoctor[agg.doctorProfileId] ?: return@map agg
            applySplit(agg, percent)
        }

    fun assertMembershipMayHaveRevenueShare(membership: ClinicMembership) {
        val role = membership.membershipRole
        val hasDoctorProfile = membership.doctorProfile?.id != null
        if (!hasDoctorProfile) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Membership is not linked to a doctor profile",
            )
        }
        if (role != ClinicMembership.MembershipRole.DOCTOR && role != ClinicMembership.MembershipRole.OWNER) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Revenue share applies only to doctor memberships",
            )
        }
    }

    companion object {
        /** Integer minor-units; remainder stays with clinic to avoid rounding drift. */
        fun splitAmount(amountMinor: Long, doctorPercent: Int): Pair<Long, Long> {
            val doctor = (amountMinor * doctorPercent) / 100
            return doctor to (amountMinor - doctor)
        }
    }
}
