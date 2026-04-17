package com.shifa.repo

import com.shifa.domain.PatientVisitAiSummary
import org.springframework.data.jpa.repository.JpaRepository

interface PatientVisitAiSummaryRepository : JpaRepository<PatientVisitAiSummary, Long> {
    fun findByAppointmentIdAndLanguage(appointmentId: Long, language: String): PatientVisitAiSummary?
    fun findByAppointmentIdOrderByUpdatedAtDesc(appointmentId: Long): List<PatientVisitAiSummary>
}

