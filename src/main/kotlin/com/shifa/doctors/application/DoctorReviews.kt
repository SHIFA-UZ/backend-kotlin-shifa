package com.shifa.doctors.application

import com.shifa.doctors.domain.DoctorReview
import com.shifa.doctors.infrastructure.DoctorReviewRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to DoctorReview records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [DoctorReviewRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class DoctorReviews(private val repo: DoctorReviewRepository) {
    fun findByDoctorId(doctorId: Long): List<DoctorReview> = repo.findByDoctorId(doctorId)
    fun findByDoctorIdOrderByCreatedAtDesc(doctorId: Long): List<DoctorReview> = repo.findByDoctorIdOrderByCreatedAtDesc(doctorId)
    fun findByPatientId(patientId: Long): List<DoctorReview> = repo.findByPatientId(patientId)
    fun findByAppointmentId(appointmentId: Long): DoctorReview? = repo.findByAppointmentId(appointmentId)
    fun findAverageRatingByDoctorId(doctorId: Long): Double? = repo.findAverageRatingByDoctorId(doctorId)
    fun countByDoctorId(doctorId: Long): Long = repo.countByDoctorId(doctorId)
    fun deleteAll(entities: Iterable<DoctorReview>) = repo.deleteAll(entities)
    fun deleteAll() = repo.deleteAll()
    fun save(entity: DoctorReview): DoctorReview = repo.save(entity)
}
