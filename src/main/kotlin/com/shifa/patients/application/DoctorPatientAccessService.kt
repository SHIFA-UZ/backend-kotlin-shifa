package com.shifa.patients.application

import com.shifa.appointments.infrastructure.AppointmentRepository
import com.shifa.patients.infrastructure.CarePartnershipRepository
import com.shifa.patients.infrastructure.PatientProfileRepository
import org.springframework.stereotype.Service

/**
 * Same access rule as patient briefing / documents: appointment together,
 * patient created by doctor, or active care partnership.
 */
@Service
class DoctorPatientAccessService(
    private val appointmentRepo: AppointmentRepository,
    private val patientsRepo: PatientProfileRepository,
    private val carePartnershipRepo: CarePartnershipRepository,
) {
    fun doctorMayAccessPatient(doctorId: Long, patientId: Long): Boolean {
        if (appointmentRepo.existsByDoctorIdAndPatientId(doctorId, patientId)) return true
        val patient = patientsRepo.findById(patientId).orElse(null) ?: return false
        if (patient.createdByDoctor?.id == doctorId) return true
        return carePartnershipRepo.existsActiveForDoctorAndPatient(doctorId, patientId)
    }
}
