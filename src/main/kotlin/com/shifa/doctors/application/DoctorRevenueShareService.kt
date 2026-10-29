package com.shifa.doctors.application

import com.shifa.clinics.application.ClinicFinanceLedgerService
import com.shifa.clinics.domain.ClinicMembership
import com.shifa.doctors.infrastructure.DoctorProfileRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.transaction.annotation.Transactional


@Service
class DoctorRevenueShareService(
    private val clinics: com.shifa.clinics.application.Clinics,
    private val memberships: com.shifa.clinics.application.ClinicMemberships,
    private val doctors: DoctorProfileRepository,
) {

    internal fun validatePercent(percent: Int?) {
        if (percent == null) return
        if (percent !in 0..100) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Revenue share percent must be between 0 and 100",
            )
        }
    }

    internal fun resolveEffectivePercent(
        membershipOverride: Int?,
        clinicDefault: Int?,
    ): Int? = membershipOverride ?: clinicDefault

    /**
     * Effective doctor share % keyed by doctor profile id for active clinic doctors.
     * Doctors without a configured override or clinic default are omitted.
     */
    @Transactional(readOnly = true)
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

    internal fun applySplit(agg: ClinicFinanceLedgerService.DoctorEarningAgg, percent: Int): ClinicFinanceLedgerService.DoctorEarningAgg {
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

    internal fun applySplits(
        earnings: List<ClinicFinanceLedgerService.DoctorEarningAgg>,
        shareByDoctor: Map<Long, Int>,
    ): List<ClinicFinanceLedgerService.DoctorEarningAgg> =
        earnings.map { agg ->
            val percent = shareByDoctor[agg.doctorProfileId] ?: return@map agg
            applySplit(agg, percent)
        }

    @Transactional(readOnly = true)
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
