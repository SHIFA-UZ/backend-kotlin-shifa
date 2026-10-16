package com.shifa.intelligence.appointment

import com.shifa.repo.PatientProfileRepository
import com.shifa.service.DoctorPatientAccessService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

data class PatientMatch(val id: Long, val name: String)

interface AppointmentIntelligencePatients {
    fun mayAccess(doctorId: Long, patientId: Long): Boolean
    fun findByName(doctorId: Long, name: String): List<PatientMatch>
    fun nameOf(patientId: Long): String?
}

@Service
class DoctorAppointmentIntelligencePatients(
    private val access: DoctorPatientAccessService,
    private val patients: PatientProfileRepository,
) : AppointmentIntelligencePatients {
    override fun mayAccess(doctorId: Long, patientId: Long): Boolean =
        access.doctorMayAccessPatient(doctorId, patientId)

    override fun findByName(doctorId: Long, name: String): List<PatientMatch> =
        patients.findClinicRosterScopedToDoctor(doctorId, name.trim(), PageRequest.of(0, 8))
            .content
            .mapNotNull { patient ->
                val id = patient.id ?: return@mapNotNull null
                PatientMatch(id, patient.fullName.ifBlank { "Patient" })
            }

    override fun nameOf(patientId: Long): String? =
        patients.findById(patientId).orElse(null)?.fullName?.ifBlank { "Patient" }
}
