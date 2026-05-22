package com.shifa.repo

import com.shifa.domain.AiUsageCounter
import com.shifa.domain.Role
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface AiUsageCounterRepository : JpaRepository<AiUsageCounter, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT c FROM AiUsageCounter c WHERE c.userId = :userId AND c.usageDate = :usageDate"
    )
    fun findByUserIdAndUsageDateForUpdate(userId: Long, usageDate: LocalDate): AiUsageCounter?

    @Query(
        """SELECT COALESCE(SUM(c.requestCount), 0L) FROM AiUsageCounter c WHERE c.userId = :userId AND c.role = :role 
           AND c.usageDate >= :from AND c.usageDate <= :toInclusive"""
    )
    fun sumRequestCountByUserAndUsageDateBetween(
        @Param("userId") userId: Long,
        @Param("role") role: Role,
        @Param("from") from: LocalDate,
        @Param("toInclusive") toInclusive: LocalDate,
    ): Long

    @Query(
        """SELECT c.usageDate, SUM(c.requestCount) FROM AiUsageCounter c WHERE c.userId = :userId AND c.role = :role 
           AND c.usageDate >= :from AND c.usageDate <= :toInclusive GROUP BY c.usageDate ORDER BY c.usageDate"""
    )
    fun sumRequestCountGroupedByUsageDate(
        @Param("userId") userId: Long,
        @Param("role") role: Role,
        @Param("from") from: LocalDate,
        @Param("toInclusive") toInclusive: LocalDate,
    ): List<Array<Any>>

    @Query(
        """SELECT COALESCE(SUM(c.requestCount), 0L) FROM AiUsageCounter c WHERE c.userId = :userId AND c.role = :role"""
    )
    fun sumRequestCountAllTimeForUser(
        @Param("userId") userId: Long,
        @Param("role") role: Role,
    ): Long

    @Query(
        """SELECT c.usageDate, SUM(c.requestCount) FROM AiUsageCounter c WHERE c.userId = :userId AND c.role = :role 
           GROUP BY c.usageDate ORDER BY c.usageDate"""
    )
    fun sumRequestCountGroupedAllTime(@Param("userId") userId: Long, @Param("role") role: Role): List<Array<Any>>

    @Query(
        """SELECT MAX(c.usageDate) FROM AiUsageCounter c WHERE c.userId = :userId AND c.role = :role
           AND c.usageDate >= :from AND c.usageDate <= :toInclusive"""
    )
    fun findMaxUsageDateInRange(
        @Param("userId") userId: Long,
        @Param("role") role: Role,
        @Param("from") from: LocalDate,
        @Param("toInclusive") toInclusive: LocalDate,
    ): LocalDate?

    @Query("SELECT MAX(c.usageDate) FROM AiUsageCounter c WHERE c.userId = :userId AND c.role = :role")
    fun findMaxUsageDate(@Param("userId") userId: Long, @Param("role") role: Role): LocalDate?
}
