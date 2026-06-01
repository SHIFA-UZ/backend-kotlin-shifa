package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.DoctorProfile
import com.shifa.domain.ScheduleBlock
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.ScheduleBlockRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class ScheduleBlockService(
    private val blocksRepo: ScheduleBlockRepository,
    private val appts: AppointmentRepository,
    private val cancellationService: AppointmentCancellationService,
) {

    data class CreateBlockResult(
        val block: ScheduleBlock,
        val cancelledAppointmentCount: Int,
    )

    fun countCancellableOverlapping(
        doctorId: Long,
        startAt: Instant,
        endAt: Instant,
    ): Int = findCancellableOverlapping(doctorId, startAt, endAt).size

    fun findCancellableOverlapping(
        doctorId: Long,
        startAt: Instant,
        endAt: Instant,
    ): List<Appointment> {
        val now = Instant.now()
        return appts.findOverlapping(doctorId, startAt, endAt)
            .filter {
                it.status != Appointment.Status.CANCELLED &&
                    it.status != Appointment.Status.COMPLETED &&
                    it.endAt.isAfter(now)
            }
    }

    @Transactional
    fun createBlock(
        doctor: DoctorProfile,
        startAt: Instant,
        endAt: Instant,
        reason: String?,
        cancelOverlappingAppointments: Boolean,
    ): CreateBlockResult {
        validateBlockRange(startAt, endAt, doctor.timeZone)

        var cancelledCount = 0
        if (cancelOverlappingAppointments) {
            for (appointment in findCancellableOverlapping(doctor.id!!, startAt, endAt)) {
                if (cancellationService.cancelByDoctorForScheduleBlock(appointment)) {
                    cancelledCount++
                }
            }
        }

        val block = blocksRepo.save(
            ScheduleBlock(
                doctor = doctor,
                startAt = startAt,
                endAt = endAt,
                reason = reason,
            )
        )
        return CreateBlockResult(block, cancelledCount)
    }

    private fun validateBlockRange(startAt: Instant, endAt: Instant, timeZone: String) {
        if (!endAt.isAfter(startAt)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "End time must be after start time")
        }

        val now = Instant.now()
        if (!endAt.isAfter(now)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot block time entirely in the past")
        }

        val zone = ZoneId.of(timeZone)
        val spanDays = ChronoUnit.DAYS.between(
            startAt.atZone(zone).toLocalDate(),
            endAt.atZone(zone).toLocalDate(),
        )
        if (spanDays > MAX_BLOCK_SPAN_DAYS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Block cannot span more than $MAX_BLOCK_SPAN_DAYS days",
            )
        }
    }

    companion object {
        const val MAX_BLOCK_SPAN_DAYS = 90L
    }
}
