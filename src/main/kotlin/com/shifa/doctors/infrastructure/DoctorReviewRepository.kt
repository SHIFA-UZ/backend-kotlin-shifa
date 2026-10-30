package com.shifa.doctors.infrastructure

import com.shifa.doctors.domain.DoctorReview
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DoctorReviewRepository : JpaRepository<DoctorReview, Long> {
    fun findByDoctorId(doctorId: Long): List<DoctorReview>
    
    fun findByDoctorIdOrderByCreatedAtDesc(doctorId: Long): List<DoctorReview>
    
    fun findByPatientId(patientId: Long): List<DoctorReview>
    
    fun findByAppointmentId(appointmentId: Long): DoctorReview?
    
    @Query("SELECT AVG(r.rating) FROM DoctorReview r WHERE r.doctor.id = :doctorId")
    fun findAverageRatingByDoctorId(@Param("doctorId") doctorId: Long): Double?
    
    @Query("SELECT COUNT(r) FROM DoctorReview r WHERE r.doctor.id = :doctorId")
    fun countByDoctorId(@Param("doctorId") doctorId: Long): Long

    /** Average rating and review count per doctor for several doctors in one query (public listing). */
    @Query(
        "SELECT r.doctor.id AS doctorId, AVG(r.rating) AS averageRating, COUNT(r) AS reviewCount " +
            "FROM DoctorReview r WHERE r.doctor.id IN :doctorIds GROUP BY r.doctor.id",
    )
    fun summarizeByDoctorIds(@Param("doctorIds") doctorIds: Collection<Long>): List<DoctorRatingSummary>
}
