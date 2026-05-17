package com.shifa.service

import com.shifa.repo.AppointmentRepository
import com.shifa.repo.PatientProfileRepository
import org.springframework.stereotype.Service

/**
 * Same access rule as patient briefing / documents: appointment together or patient created by doctor.
 */
@Service
class DoctorPatientAccessService(
    private val appointmentRepo: AppointmentRepository,
    private val patientsRepo: PatientProfileRepository,
) {
    fun doctorMayAccessPatient(doctorId: Long, patientId: Long): Boolean {
        if (appointmentRepo.existsByDoctorIdAndPatientId(doctorId, patientId)) return true
        val patient = patientsRepo.findById(patientId).orElse(null) ?: return false
        return patient.createdByDoctor?.id == doctorId
    }
}
