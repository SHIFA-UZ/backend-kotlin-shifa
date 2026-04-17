package com.shifa.repo

import com.shifa.domain.UserActivityLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

@Repository
interface UserActivityLogRepository : JpaRepository<UserActivityLog, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): Page<UserActivityLog>
    
    @Query("SELECT u FROM UserActivityLog u WHERE u.activityType = :activityType AND u.createdAt >= :since ORDER BY u.createdAt DESC")
    fun findByActivityTypeSince(
        @Param("activityType") activityType: String,
        @Param("since") since: OffsetDateTime,
        pageable: Pageable
    ): Page<UserActivityLog>
    
    fun findByCreatedAtBetweenOrderByCreatedAtDesc(
        start: OffsetDateTime,
        end: OffsetDateTime,
        pageable: Pageable
    ): Page<UserActivityLog>

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM UserActivityLog u WHERE u.user.id = :userId")
    fun deleteByUserId(@Param("userId") userId: Long): Int
}
