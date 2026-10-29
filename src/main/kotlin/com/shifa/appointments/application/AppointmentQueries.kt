package com.shifa.appointments.application

import com.shifa.appointments.domain.Appointment
import com.shifa.appointments.infrastructure.AppointmentRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Read-side lookups other features need; they must not touch [AppointmentRepository] themselves. */
@Service
class AppointmentQueries(private val appointments: AppointmentRepository) {

    /** The appointment, or 404 when unknown and 400 when it belongs to another patient. */
    fun ownedByPatient(appointmentId: Long, patientId: Long): Appointment {
        val found = appointments.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId") }
        if (found.patient.id != patientId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Appointment does not belong to this patient")
        }
        return found
    }
}
