package com.shifa.service

import com.shifa.domain.User
import com.shifa.domain.UserActivityLog
import com.shifa.repo.UserActivityLogRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class UserActivityService(
    private val userActivityLogRepository: UserActivityLogRepository
) {
    
    @Transactional
    fun logActivity(
        user: User,
        activityType: String,
        success: Boolean = true,
        failureReason: String? = null,
        request: HttpServletRequest? = null
    ) {
        val log = UserActivityLog(
            user = user,
            activityType = activityType,
            ipAddress = request?.remoteAddr,
            userAgent = request?.getHeader("User-Agent"),
            success = success,
            failureReason = failureReason
        )
        userActivityLogRepository.save(log)
    }
    
    fun getUserActivity(userId: Long, pageable: Pageable): Page<UserActivityLog> {
        return userActivityLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
    }
    
    fun getActivityByType(activityType: String, since: OffsetDateTime, pageable: Pageable): Page<UserActivityLog> {
        return userActivityLogRepository.findByActivityTypeSince(activityType, since, pageable)
    }
    
    fun getActivityBetween(start: OffsetDateTime, end: OffsetDateTime, pageable: Pageable): Page<UserActivityLog> {
        return userActivityLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable)
    }
}
