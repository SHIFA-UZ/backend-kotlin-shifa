package com.shifa.web

import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.ScheduleBlockRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.ClinicAccessService
import com.shifa.service.ScheduleBlockService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RestController
@RequestMapping("/api/schedule/blocks")
class ScheduleBlockController(
    private val blocksRepo: ScheduleBlockRepository,
    private val doctors: DoctorProfileRepository,
    private val clinicAccess: ClinicAccessService,
    private val scheduleBlockService: ScheduleBlockService,
) {

    data class BlockDto(
        val id: Long? = null,
        val startAt: String,
        val endAt: String,
        val reason: String? = null,
        /** When true, overlapping future/in-progress appointments are cancelled. */
        val cancelOverlappingAppointments: Boolean = false,
        /** Calendar doctor (defaults to authenticated doctor). */
        val resourceDoctorId: Long? = null,
        val cancelledAppointmentCount: Int? = null,
    )

    data class OverlapCountDto(val count: Int)

    private fun resolveDoctorId(principal: Any, resourceDoctorId: Long?): Long {
        clinicAccess.assertPracticeActor(principal)
        val targetDoctorId = resourceDoctorId ?: when (principal) {
            is DoctorPrincipal -> principal.profile.id
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "resourceDoctorId is required")
        }
        clinicAccess.assertCanViewDoctorCalendar(principal, targetDoctorId)
        return targetDoctorId
    }

    private fun loadDoctor(doctorId: Long) =
        doctors.findById(doctorId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found")
        }

    @GetMapping
    fun listForDay(
        @AuthenticationPrincipal principal: Any,
        @RequestParam day: String,
        @RequestParam(required = false) doctorId: Long?,
    ): List<BlockDto> {
        val targetDoctorId = resolveDoctorId(principal, doctorId)
        val doctor = loadDoctor(targetDoctorId)
        val localDate = LocalDate.parse(day)
        val zone = ZoneId.of(doctor.timeZone)
        val dayStart = localDate.atStartOfDay(zone).toInstant()
        val dayEnd = localDate.plusDays(1).atStartOfDay(zone).toInstant()

        return blocksRepo.findOverlapping(doctor.id!!, dayStart, dayEnd).map { it.toDto() }
    }

    @GetMapping("/overlapping-count")
    fun overlappingAppointmentCount(
        @AuthenticationPrincipal principal: Any,
        @RequestParam startAt: String,
        @RequestParam endAt: String,
        @RequestParam(required = false) doctorId: Long?,
    ): OverlapCountDto {
        val targetDoctorId = resolveDoctorId(principal, doctorId)
        val start = Instant.parse(startAt)
        val end = Instant.parse(endAt)
        if (!end.isAfter(start)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "End time must be after start time")
        }
        val count = scheduleBlockService.countCancellableOverlapping(targetDoctorId, start, end)
        return OverlapCountDto(count)
    }

    @PostMapping
    @Transactional
    fun create(
        @AuthenticationPrincipal principal: Any,
        @RequestBody body: BlockDto,
    ): BlockDto {
        val targetDoctorId = resolveDoctorId(principal, body.resourceDoctorId)
        val doctor = loadDoctor(targetDoctorId)

        val startAt = Instant.parse(body.startAt)
        val endAt = Instant.parse(body.endAt)

        val result = scheduleBlockService.createBlock(
            doctor = doctor,
            startAt = startAt,
            endAt = endAt,
            reason = body.reason?.trim()?.takeIf { it.isNotEmpty() },
            cancelOverlappingAppointments = body.cancelOverlappingAppointments,
        )
        return result.block.toDto(cancelledAppointmentCount = result.cancelledAppointmentCount)
    }

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(
        @AuthenticationPrincipal principal: Any,
        @PathVariable id: Long,
        @RequestParam(required = false) doctorId: Long?,
    ) {
        val targetDoctorId = resolveDoctorId(principal, doctorId)
        val existing = blocksRepo.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Block not found")
        }
        if (existing.doctor.id != targetDoctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You can only remove your own blocks")
        }
        blocksRepo.delete(existing)
    }

    private fun com.shifa.domain.ScheduleBlock.toDto(
        cancelledAppointmentCount: Int? = null,
    ) = BlockDto(
        id = id,
        startAt = startAt.toString(),
        endAt = endAt.toString(),
        reason = reason,
        cancelledAppointmentCount = cancelledAppointmentCount,
    )
}
