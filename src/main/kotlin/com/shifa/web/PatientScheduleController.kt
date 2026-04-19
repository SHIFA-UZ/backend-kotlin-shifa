// src/main/kotlin/com/shifa/web/PatientScheduleController.kt
package com.shifa.web

import com.shifa.repo.DoctorProfileRepository
import com.shifa.security.PatientPrincipal
import com.shifa.service.PatientDaySlotsService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

@RestController
@RequestMapping("/api/patients/me/schedule")
class PatientScheduleController(
    private val doctorProfiles: DoctorProfileRepository,
    private val patientDaySlotsService: PatientDaySlotsService
) {

    /**
     * GET /api/patients/me/schedule/doctors/{doctorId}/available
     * Get available time slots for a doctor on a specific day
     */
    @GetMapping("/doctors/{doctorId}/available")
    fun getAvailableSlots(
        @AuthenticationPrincipal _principal: PatientPrincipal,
        @PathVariable doctorId: Long,
        @RequestParam day: String // yyyy-MM-dd
    ): List<PatientDaySlotsService.AvailableSlotDto> {
        val doctor = doctorProfiles.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }

        val localDate = LocalDate.parse(day)
        return patientDaySlotsService.availableSlotsForDay(doctor, localDate)
    }
}
