package com.shifa.repo

import com.shifa.domain.ScheduleBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ScheduleBlockRepository : JpaRepository<ScheduleBlock, Long> {

    @Modifying
    @Query("DELETE FROM ScheduleBlock b WHERE b.doctor.id = :doctorId")
    fun deleteByDoctorId(@Param("doctorId") doctorId: Long): Int

    @Query(
        """
        SELECT b FROM ScheduleBlock b
        WHERE b.doctor.id = :doctorId
          AND b.startAt < :rangeEnd
          AND b.endAt > :rangeStart
        ORDER BY b.startAt ASC
        """
    )
    fun findOverlapping(
        @Param("doctorId") doctorId: Long,
        @Param("rangeStart") rangeStart: java.time.Instant,
        @Param("rangeEnd") rangeEnd: java.time.Instant
    ): List<ScheduleBlock>
}
