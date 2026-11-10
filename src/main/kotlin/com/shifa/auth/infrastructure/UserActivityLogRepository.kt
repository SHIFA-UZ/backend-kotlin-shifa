package com.shifa.auth.infrastructure

import com.shifa.auth.domain.UserActivityLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface UserActivityLogRepository : JpaRepository<UserActivityLog, Long>, JpaSpecificationExecutor<UserActivityLog> {
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM UserActivityLog u WHERE u.user.id = :userId")
    fun deleteByUserId(@Param("userId") userId: Long): Int
}
