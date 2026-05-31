package com.shifa.service

import com.shifa.config.DevSmsProperties
import com.shifa.domain.DoctorProfile
import com.shifa.domain.DoctorSmsUsage
import com.shifa.domain.PatientProfile
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorSmsUsageRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class DoctorSmsUsageSummary(
    val sentCount: Long,
    val totalCostMinor: Long,
    val currency: String,
    val pricePerSmsMinor: Long,
)

@Service
class DoctorSmsBillingService(
    private val doctorSmsUsageRepository: DoctorSmsUsageRepository,
    private val doctorProfileRepository: DoctorProfileRepository,
    private val devSmsProperties: DevSmsProperties,
) {
    val pricePerSmsMinor: Long get() = devSmsProperties.pricePerSmsMinor
    val currency: String get() = devSmsProperties.billingCurrency

    fun isSmsAllowed(doctor: DoctorProfile): Boolean = doctor.smsRemindersAllowed

    fun isSmsAllowed(doctorId: Long): Boolean =
        doctorProfileRepository.findById(doctorId).map { it.smsRemindersAllowed }.orElse(false)

    @Transactional
    fun recordSentSms(
        doctor: DoctorProfile,
        patient: PatientProfile,
        appointmentId: Long?,
        devsmsSmsId: String?,
        sentAt: Instant = Instant.now(),
    ) {
        doctorSmsUsageRepository.save(
            DoctorSmsUsage(
                doctor = doctor,
                patient = patient,
                appointmentId = appointmentId,
                costMinor = pricePerSmsMinor,
                currency = currency,
                devsmsSmsId = devsmsSmsId,
                sentAt = sentAt,
            )
        )
    }

    fun summaryForDoctor(doctorId: Long, from: LocalDate?, toInclusive: LocalDate?): DoctorSmsUsageSummary {
        val (count, sum) = if (from != null && toInclusive != null) {
            val start = from.atStartOfDay(ZoneOffset.UTC).toInstant()
            val end = toInclusive.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            doctorSmsUsageRepository.countByDoctorIdAndSentAtBetween(doctorId, start, end) to
                doctorSmsUsageRepository.sumCostMinorByDoctorIdAndSentAtBetween(doctorId, start, end)
        } else {
            doctorSmsUsageRepository.countByDoctorId(doctorId) to
                doctorSmsUsageRepository.sumCostMinorByDoctorId(doctorId)
        }
        return DoctorSmsUsageSummary(
            sentCount = count,
            totalCostMinor = sum,
            currency = currency,
            pricePerSmsMinor = pricePerSmsMinor,
        )
    }

    fun aggregateMapForWindow(window: AdminDoctorActivityService.BoundedWindow?): Map<Long, Pair<Long, Long>> {
        val rows = if (window != null) {
            doctorSmsUsageRepository.aggregateByDoctorBetween(
                window.startInstant,
                window.endExclusiveInstant,
            )
        } else {
            doctorSmsUsageRepository.aggregateAllTimeByDoctor()
        }
        return rows.associate { row ->
            val doctorId = (row[0] as Number).toLong()
            val count = (row[1] as Number).toLong()
            val sum = (row[2] as Number).toLong()
            doctorId to (count to sum)
        }
    }

    @Transactional
    fun setSmsRemindersAllowed(doctorId: Long, allowed: Boolean): DoctorProfile {
        val doctor = doctorProfileRepository.findById(doctorId)
            .orElseThrow { NoSuchElementException("Doctor not found") }
        doctor.smsRemindersAllowed = allowed
        return doctorProfileRepository.save(doctor)
    }
}
