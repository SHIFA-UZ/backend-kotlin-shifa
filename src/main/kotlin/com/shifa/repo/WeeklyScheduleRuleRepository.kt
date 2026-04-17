package com.shifa.repo

import com.shifa.domain.WeeklyScheduleRule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WeeklyScheduleRuleRepository : JpaRepository<WeeklyScheduleRule, Long> {
    fun findByDoctorId(doctorId: Long): List<WeeklyScheduleRule>

    /**
     * Hard delete all weekly schedule rules for a doctor (admin calendar reset).
     */
    @Modifying
    @Query("DELETE FROM WeeklyScheduleRule r WHERE r.doctor.id = :doctorId")
    fun deleteByDoctorId(@Param("doctorId") doctorId: Long): Int
}
