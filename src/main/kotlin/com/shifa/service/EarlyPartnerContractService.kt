package com.shifa.service

import com.shifa.domain.DoctorProfile
import com.shifa.domain.EarlyPartnerContract
import com.shifa.domain.SystemConfig
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.EarlyPartnerContractRepository
import com.shifa.repo.SystemConfigRepository
import com.shifa.web.dto.EarlyPartnerContractIssueDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Service
class EarlyPartnerContractService(
    private val doctorProfileRepository: DoctorProfileRepository,
    private val earlyPartnerContractRepository: EarlyPartnerContractRepository,
    private val systemConfigRepository: SystemConfigRepository,
) {
    companion object {
        const val SEQ_KEY = "early_partner_contract_next_seq"
        const val TERM_MONTHS_KEY = "early_partner_contract_term_months"
        const val DEFAULT_NEXT_SEQ = 461
        private val UTC = ZoneOffset.UTC
    }

    @Transactional
    fun issueForDoctor(doctorId: Long): EarlyPartnerContractIssueDto {
        val doctor = doctorProfileRepository.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }

        val existing = earlyPartnerContractRepository.findByDoctorProfileId(doctorId).orElse(null)
        if (existing != null) {
            refreshSnapshot(existing, doctor)
            existing.updatedAt = OffsetDateTime.now()
            earlyPartnerContractRepository.save(existing)
            return toDto(existing, newAllocation = false)
        }

        val seq = allocateNextSeq()
        val number = formatContractNumber(seq)
        val effectiveDate = doctorJoinDate(doctor)
        val termMonths = readTermMonths()

        val contract = EarlyPartnerContract(
            doctorProfile = doctor,
            contractSeq = seq,
            contractNumber = number,
            effectiveDate = effectiveDate,
            termMonths = termMonths,
            partnerFullName = fullName(doctor),
            partnerClinic = resolveClinicName(doctor),
            partnerPhone = doctor.user.phone?.trim()?.takeUnless { it.isEmpty() },
            partnerEmail = doctor.user.email?.trim()?.takeUnless { it.isEmpty() },
        )
        earlyPartnerContractRepository.save(contract)
        return toDto(contract, newAllocation = true)
    }

    fun contractNumbersByDoctorIds(doctorIds: Collection<Long>): Map<Long, String> {
        if (doctorIds.isEmpty()) return emptyMap()
        return earlyPartnerContractRepository.findByDoctorProfileIdIn(doctorIds)
            .associate { it.doctorProfile.id to it.contractNumber }
    }

    private fun refreshSnapshot(contract: EarlyPartnerContract, doctor: DoctorProfile) {
        contract.effectiveDate = doctorJoinDate(doctor)
        contract.partnerFullName = fullName(doctor)
        contract.partnerClinic = resolveClinicName(doctor)
        contract.partnerPhone = doctor.user.phone?.trim()?.takeUnless { it.isEmpty() }
        contract.partnerEmail = doctor.user.email?.trim()?.takeUnless { it.isEmpty() }
    }

    /** Date the doctor account was created on the platform (UTC calendar date). */
    private fun doctorJoinDate(doctor: DoctorProfile): LocalDate =
        doctor.user.createdAt.atZoneSameInstant(UTC).toLocalDate()

    private fun allocateNextSeq(): Int {
        val config = systemConfigRepository.findByKeyForUpdate(SEQ_KEY).orElseGet {
            systemConfigRepository.save(
                SystemConfig(
                    key = SEQ_KEY,
                    value = DEFAULT_NEXT_SEQ.toString(),
                    description = "Next SHIFA early-partner contract sequence",
                ),
            )
        }
        val current = config.value.trim().toIntOrNull()
            ?: DEFAULT_NEXT_SEQ
        config.value = (current + 1).toString()
        config.updatedAt = OffsetDateTime.now()
        systemConfigRepository.save(config)
        return current
    }

    private fun readTermMonths(): Int {
        val raw = systemConfigRepository.findByKey(TERM_MONTHS_KEY).map { it.value.trim() }.orElse("6")
        return raw.toIntOrNull()?.coerceIn(1, 60) ?: 6
    }

    private fun formatContractNumber(seq: Int): String = "SHIFA-${seq.toString().padStart(4, '0')}"

    private fun fullName(doctor: DoctorProfile): String =
        "${doctor.firstName.trim()} ${doctor.lastName.trim()}".trim()

    private fun resolveClinicName(doctor: DoctorProfile): String? {
        val practice = doctor.practiceClinic?.name?.trim()?.takeUnless { it.isEmpty() }
        if (practice != null) return practice
        return doctor.clinic?.trim()?.takeUnless { it.isEmpty() }
    }

    private fun toDto(contract: EarlyPartnerContract, newAllocation: Boolean): EarlyPartnerContractIssueDto =
        EarlyPartnerContractIssueDto(
            contractNumber = contract.contractNumber,
            contractSeq = contract.contractSeq,
            effectiveDate = contract.effectiveDate.toString(),
            termMonths = contract.termMonths,
            partnerFullName = contract.partnerFullName,
            partnerFirstName = contract.doctorProfile.firstName.trim(),
            partnerLastName = contract.doctorProfile.lastName.trim(),
            partnerClinic = contract.partnerClinic,
            partnerPhone = contract.partnerPhone,
            partnerEmail = contract.partnerEmail,
            roleDoctor = true,
            rolePatient = false,
            roleBoth = false,
            newAllocation = newAllocation,
            issuedAt = contract.updatedAt.toString(),
        )
}
