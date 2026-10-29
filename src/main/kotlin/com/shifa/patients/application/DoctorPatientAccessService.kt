package com.shifa.patients.application

import com.shifa.patients.infrastructure.CarePartnershipRepository
import com.shifa.patients.infrastructure.PatientProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Same access rule as patient briefing / documents: appointment together,
 * patient created by doctor, or active care partnership.
 */
@Service
class DoctorPatientAccessService(
    private val appointmentRepo: com.shifa.appointments.application.Appointments,
    private val patientsRepo: PatientProfileRepository,
    private val carePartnershipRepo: CarePartnershipRepository,
) {
    /** Strict variant without the care-partnership route: an appointment together, or the doctor created the patient. */
    @Transactional(readOnly = true)
    fun doctorHasAppointmentOrCreatedPatient(doctorId: Long, patientId: Long): Boolean {
        if (appointmentRepo.existsByDoctorIdAndPatientId(doctorId, patientId)) return true
        val patient = patientsRepo.findById(patientId).orElse(null) ?: return false
        return patient.createdByDoctor?.id == doctorId
    }

    @Transactional(readOnly = true)
    fun patientExists(patientId: Long): Boolean = patientsRepo.existsById(patientId)

    @Transactional(readOnly = true)
    fun doctorMayAccessPatient(doctorId: Long, patientId: Long): Boolean {
        if (appointmentRepo.existsByDoctorIdAndPatientId(doctorId, patientId)) return true
        val patient = patientsRepo.findById(patientId).orElse(null) ?: return false
        if (patient.createdByDoctor?.id == doctorId) return true
        return carePartnershipRepo.existsActiveForDoctorAndPatient(doctorId, patientId)
    }
}
