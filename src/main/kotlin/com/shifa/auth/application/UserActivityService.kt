package com.shifa.auth.application


import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserActivityLog
import com.shifa.auth.infrastructure.UserActivityLogRepository
import com.shifa.shared.util.LogSearch
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset

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
    
    /**
     * The activity log with every given filter applied together. Without `from` the last 30 days are read,
     * unless the user is given: that reads all time, as it always did.
     */
    @Transactional(readOnly = true)
    fun search(userId: Long?, activityType: String?, from: Instant?, to: Instant?, pageable: Pageable): Page<UserActivityLog> {
        val (start, end) = LogSearch.window(from, to, unboundedWhenNoFrom = userId != null)
        val spec = Specification<UserActivityLog> { root, _, cb ->
            cb.and(
                listOfNotNull(
                    userId?.let { cb.equal(root.get<User>("user").get<Long>("id"), it) },
                    activityType?.let { cb.equal(root.get<String>("activityType"), it) },
                    start?.let { cb.greaterThanOrEqualTo(root.get("createdAt"), it.atOffset(ZoneOffset.UTC)) },
                    end?.let { cb.lessThan(root.get("createdAt"), it.atOffset(ZoneOffset.UTC)) },
                ),
            )
        }
        return userActivityLogRepository.findAll(spec, LogSearch.pageable(pageable, SORT_KEYS))
    }

    companion object {
        /** Sort keys of the activity list: API name to entity path. */
        private val SORT_KEYS = mapOf(
            "createdAt" to "createdAt",
            "id" to "id",
            "activityType" to "activityType",
            "success" to "success",
            "userId" to "user.id",
        )
    }
}
