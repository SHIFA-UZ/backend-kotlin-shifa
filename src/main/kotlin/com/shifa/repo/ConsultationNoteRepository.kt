package com.shifa.repo

import com.shifa.domain.ConsultationNote
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param

interface ConsultationNoteRepository : JpaRepository<ConsultationNote, Long> {
    fun findByDoctorIdAndPatientIdOrderByCreatedAtDesc(
        doctorId: Long,
        patientId: Long
    ): List<ConsultationNote>

    fun findByAppointmentIdOrderByCreatedAtAsc(appointmentId: Long): List<ConsultationNote>
    fun findFirstByAppointmentIdOrderByCreatedAtDesc(appointmentId: Long): ConsultationNote?

    @org.springframework.data.jpa.repository.Query("SELECT n FROM ConsultationNote n WHERE n.doctorId = :doctorId")
    fun findByDoctorId(@Param("doctorId") doctorId: Long): List<ConsultationNote>

    @org.springframework.data.jpa.repository.Query("SELECT n FROM ConsultationNote n WHERE n.patientId = :patientId")
    fun findByPatientId(@Param("patientId") patientId: Long): List<ConsultationNote>
}
