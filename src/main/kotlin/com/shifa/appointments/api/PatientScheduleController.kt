// src/main/kotlin/com/shifa/appointments/api/PatientScheduleController.kt
package com.shifa.appointments.api

import com.shifa.appointments.application.PatientDaySlotsService
import com.shifa.shared.security.PatientPrincipal
import java.time.LocalDate
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/patients/me/schedule")
class PatientScheduleController(
    private val doctorProfiles: com.shifa.doctors.application.DoctorProfiles,
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
        @RequestParam day: String, // yyyy-MM-dd
        @RequestParam(required = false) locationId: Long?
    ): List<PatientDaySlotsService.AvailableSlotDto> {
        val doctor = doctorProfiles.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }

        val localDate = LocalDate.parse(day)
        return patientDaySlotsService.availableSlotsForDay(doctor, localDate, locationId)
    }
}
