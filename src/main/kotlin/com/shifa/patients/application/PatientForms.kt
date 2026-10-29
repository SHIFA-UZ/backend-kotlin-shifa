package com.shifa.patients.application

import com.shifa.patients.domain.PatientForm
import com.shifa.patients.infrastructure.PatientFormRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to PatientForm records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [PatientFormRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class PatientForms(private val repo: PatientFormRepository) {
    fun findByPatientIdOrderByDateDesc(patientId: Long): List<PatientForm> = repo.findByPatientIdOrderByDateDesc(patientId)
    fun findByPatientIdAndTemplateIdOrderByDateDesc(patientId: Long, templateId: String): List<PatientForm> = repo.findByPatientIdAndTemplateIdOrderByDateDesc(patientId, templateId)
    fun countByCreatedByDoctorInDateRange(doctorId: Long, start: java.time.OffsetDateTime, endExclusive: java.time.OffsetDateTime): Long = repo.countByCreatedByDoctorInDateRange(doctorId, start, endExclusive)
    fun countByCreatedByDoctorAllTime(doctorId: Long): Long = repo.countByCreatedByDoctorAllTime(doctorId)
    fun findMaxCreatedAtByDoctor(doctorId: Long): java.time.OffsetDateTime? = repo.findMaxCreatedAtByDoctor(doctorId)
    fun deleteAll(entities: Iterable<PatientForm>) = repo.deleteAll(entities)
    fun deleteAll() = repo.deleteAll()
    fun findById(id: Long): Optional<PatientForm> = repo.findById(id)
}
