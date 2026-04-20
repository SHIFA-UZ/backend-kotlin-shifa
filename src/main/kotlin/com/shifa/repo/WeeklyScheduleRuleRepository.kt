package com.shifa.repo

import com.shifa.domain.WeeklyScheduleRule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WeeklyScheduleRuleRepository : JpaRepository<WeeklyScheduleRule, Long> {
    fun findByDoctorId(doctorId: Long): List<WeeklyScheduleRule>

    /** Rules for a specific location only (used when replacing rules for a single location). */
    @Query("SELECT r FROM WeeklyScheduleRule r WHERE r.doctor.id = :doctorId AND r.location.id = :locationId")
    fun findByDoctorIdAndLocationId(
        @Param("doctorId") doctorId: Long,
        @Param("locationId") locationId: Long
    ): List<WeeklyScheduleRule>

    /**
     * Hard delete all weekly schedule rules for a doctor (admin calendar reset).
     */
    @Modifying
    @Query("DELETE FROM WeeklyScheduleRule r WHERE r.doctor.id = :doctorId")
    fun deleteByDoctorId(@Param("doctorId") doctorId: Long): Int

    /** Delete all weekly rules for a single location (used by PUT /rules?locationId=). */
    @Modifying
    @Query("DELETE FROM WeeklyScheduleRule r WHERE r.doctor.id = :doctorId AND r.location.id = :locationId")
    fun deleteByDoctorIdAndLocationId(
        @Param("doctorId") doctorId: Long,
        @Param("locationId") locationId: Long
    ): Int
}
