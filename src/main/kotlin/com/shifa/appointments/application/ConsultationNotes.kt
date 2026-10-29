package com.shifa.appointments.application

import com.shifa.appointments.domain.ConsultationNote
import com.shifa.appointments.infrastructure.ConsultationNoteRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to ConsultationNote records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [ConsultationNoteRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class ConsultationNotes(private val repo: ConsultationNoteRepository) {
    fun findByDoctorIdAndPatientIdOrderByCreatedAtDesc(doctorId: Long, patientId: Long): List<ConsultationNote> = repo.findByDoctorIdAndPatientIdOrderByCreatedAtDesc(doctorId, patientId)
    fun findByAppointmentIdOrderByCreatedAtAsc(appointmentId: Long): List<ConsultationNote> = repo.findByAppointmentIdOrderByCreatedAtAsc(appointmentId)
    fun findFirstByAppointmentIdOrderByCreatedAtDesc(appointmentId: Long): ConsultationNote? = repo.findFirstByAppointmentIdOrderByCreatedAtDesc(appointmentId)
    fun findByDoctorId(doctorId: Long): List<ConsultationNote> = repo.findByDoctorId(doctorId)
    fun findByPatientId(patientId: Long): List<ConsultationNote> = repo.findByPatientId(patientId)
    fun countByDoctorInDateRange(doctorId: Long, start: java.time.Instant, endExclusive: java.time.Instant): Long = repo.countByDoctorInDateRange(doctorId, start, endExclusive)
    fun countByDoctorAllTime(doctorId: Long): Long = repo.countByDoctorAllTime(doctorId)
    fun findMaxCreatedAtByDoctorId(doctorId: Long): java.time.Instant? = repo.findMaxCreatedAtByDoctorId(doctorId)
    fun listByDoctorCreatedBetween(doctorId: Long, start: java.time.Instant, endExclusive: java.time.Instant): List<ConsultationNote> = repo.listByDoctorCreatedBetween(doctorId, start, endExclusive)
    fun deleteAll(entities: Iterable<ConsultationNote>) = repo.deleteAll(entities)
    fun deleteAll() = repo.deleteAll()
    fun findById(id: Long): Optional<ConsultationNote> = repo.findById(id)
    fun save(entity: ConsultationNote): ConsultationNote = repo.save(entity)
}
