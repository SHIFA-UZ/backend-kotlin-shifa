package com.shifa.repo

import com.shifa.domain.DateSpecificScheduleRule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface DateSpecificScheduleRuleRepository : JpaRepository<DateSpecificScheduleRule, Long> {

    /**
     * Hard delete all date-specific schedule rules for a doctor (admin calendar reset).
     */
    @Modifying
    @Query("DELETE FROM DateSpecificScheduleRule r WHERE r.doctor.id = :doctorId")
    fun deleteByDoctorId(@Param("doctorId") doctorId: Long): Int

    /**
     * Find all date-specific schedule rules for a doctor.
     */
    fun findByDoctorId(doctorId: Long): List<DateSpecificScheduleRule>

    /** Date-specific rules for a single location (used by per-location editor). */
    @Query("SELECT r FROM DateSpecificScheduleRule r WHERE r.doctor.id = :doctorId AND r.location.id = :locationId")
    fun findByDoctorIdAndLocationId(
        @Param("doctorId") doctorId: Long,
        @Param("locationId") locationId: Long
    ): List<DateSpecificScheduleRule>
    
    /**
     * Find all date-specific schedule rules that apply to a specific date.
     */
    @Query("""
        SELECT r FROM DateSpecificScheduleRule r
        WHERE r.doctor.id = :doctorId
          AND r.startDate <= :date
          AND r.endDate >= :date
        ORDER BY r.startTime ASC
    """)
    fun findByDoctorIdAndDate(doctorId: Long, date: LocalDate): List<DateSpecificScheduleRule>
    
    /**
     * Find all date-specific schedule rules that overlap with a date range.
     * Used to check for conflicts when creating new rules.
     */
    @Query("""
        SELECT r FROM DateSpecificScheduleRule r
        WHERE r.doctor.id = :doctorId
          AND r.startDate <= :rangeEndDate
          AND r.endDate >= :rangeStartDate
        ORDER BY r.startDate ASC, r.startTime ASC
    """)
    fun findOverlappingWithDateRange(
        doctorId: Long,
        rangeStartDate: LocalDate,
        rangeEndDate: LocalDate
    ): List<DateSpecificScheduleRule>
}
