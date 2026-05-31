package com.shifa.repo

import com.shifa.domain.DoctorSmsUsage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface DoctorSmsUsageRepository : JpaRepository<DoctorSmsUsage, Long> {

    @Query(
        """
        SELECT COUNT(u) FROM DoctorSmsUsage u
        WHERE u.doctor.id = :doctorId
          AND u.sentAt >= :start AND u.sentAt < :end
        """
    )
    fun countByDoctorIdAndSentAtBetween(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): Long

    @Query(
        """
        SELECT COALESCE(SUM(u.costMinor), 0) FROM DoctorSmsUsage u
        WHERE u.doctor.id = :doctorId
          AND u.sentAt >= :start AND u.sentAt < :end
        """
    )
    fun sumCostMinorByDoctorIdAndSentAtBetween(
        @Param("doctorId") doctorId: Long,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): Long

    @Query(
        """
        SELECT COUNT(u) FROM DoctorSmsUsage u WHERE u.doctor.id = :doctorId
        """
    )
    fun countByDoctorId(@Param("doctorId") doctorId: Long): Long

    @Query(
        """
        SELECT COALESCE(SUM(u.costMinor), 0) FROM DoctorSmsUsage u WHERE u.doctor.id = :doctorId
        """
    )
    fun sumCostMinorByDoctorId(@Param("doctorId") doctorId: Long): Long

    /** [doctorId, count, sumMinor] per doctor in UTC instant range. */
    @Query(
        """
        SELECT u.doctor.id, COUNT(u), COALESCE(SUM(u.costMinor), 0)
        FROM DoctorSmsUsage u
        WHERE u.sentAt >= :start AND u.sentAt < :end
        GROUP BY u.doctor.id
        """
    )
    fun aggregateByDoctorBetween(
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<Array<Any>>

    /** [doctorId, count, sumMinor] all time per doctor. */
    @Query(
        """
        SELECT u.doctor.id, COUNT(u), COALESCE(SUM(u.costMinor), 0)
        FROM DoctorSmsUsage u
        GROUP BY u.doctor.id
        """
    )
    fun aggregateAllTimeByDoctor(): List<Array<Any>>
}
