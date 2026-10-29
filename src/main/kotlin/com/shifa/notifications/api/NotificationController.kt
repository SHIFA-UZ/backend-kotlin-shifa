package com.shifa.notifications.api

import com.shifa.notifications.api.dto.NotificationDto
import com.shifa.notifications.application.NotificationFeed
import com.shifa.patients.application.CurrentPatientResolver
import com.shifa.shared.security.DoctorPrincipal
import com.shifa.shared.security.PatientPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val feed: NotificationFeed,
    private val currentPatient: CurrentPatientResolver,
) {

    /** Doctors and patients have feeds; clinic staff (and anything else) see an empty one, as before. */
    private fun owner(principal: Any): NotificationFeed.Owner? = when (principal) {
        is DoctorPrincipal -> NotificationFeed.Owner.Doctor(principal.profile.id)
        is PatientPrincipal -> NotificationFeed.Owner.Patient(
            currentPatient.resolve(principal).id ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patient profile not found"),
        )
        else -> null
    }

    @GetMapping
    fun getNotifications(@AuthenticationPrincipal principal: Any): List<NotificationDto> =
        owner(principal)?.let { feed.list(it).map(NotificationDto::from) } ?: emptyList()

    @GetMapping("/unread/count")
    fun getUnreadCount(@AuthenticationPrincipal principal: Any): Map<String, Int> =
        mapOf("count" to (owner(principal)?.let { feed.unreadCount(it) } ?: 0))

    @PutMapping("/{notificationId}/read")
    fun markAsRead(@AuthenticationPrincipal principal: Any, @PathVariable notificationId: Long) {
        feed.markRead(owner(principal), notificationId)
    }

    @PutMapping("/read-all")
    fun markAllAsRead(@AuthenticationPrincipal principal: Any) {
        owner(principal)?.let { feed.markAllRead(it) }
    }
}
