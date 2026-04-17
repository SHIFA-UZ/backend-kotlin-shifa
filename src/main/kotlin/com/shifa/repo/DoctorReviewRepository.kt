package com.shifa.repo

import com.shifa.domain.DoctorReview
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
}
