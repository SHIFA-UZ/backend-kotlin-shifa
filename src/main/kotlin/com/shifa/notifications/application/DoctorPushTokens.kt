package com.shifa.notifications.application

import com.shifa.notifications.domain.DoctorPushToken
import com.shifa.notifications.infrastructure.DoctorPushTokenRepository
import java.time.OffsetDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A doctor's device tokens: added at sign-in and on refresh, removed at sign-out, dropped when FCM rejects them. */
@Service
class DoctorPushTokens(
    private val repo: DoctorPushTokenRepository,
    private val doctorProfiles: com.shifa.doctors.application.DoctorProfiles,
) {

    /** Idempotent. A token already registered by another doctor moves to this one (the device changed account). */
    @Transactional
    fun register(doctorProfileId: Long, token: String, platform: String?) {
        // The same device's legacy single token (Flutter build) on any other doctor stops there: one device, one doctor.
        clearLegacy(token) { it.id != doctorProfileId }
        val now = OffsetDateTime.now()
        val row = repo.findByToken(token)
        if (row == null) {
            repo.save(DoctorPushToken(doctorProfileId = doctorProfileId, token = token, platform = platform))
            return
        }
        row.doctorProfileId = doctorProfileId
        row.platform = platform ?: row.platform
        row.lastSeenAt = now
        repo.save(row)
    }

    /** Idempotent; only this doctor's own token is removed. */
    @Transactional
    fun unregister(doctorProfileId: Long, token: String) {
        repo.findByToken(token)?.takeIf { it.doctorProfileId == doctorProfileId }?.let(repo::delete)
        // Signing out on the device ends its legacy token too, so the fan-out can't reach it through that column.
        clearLegacy(token) { it.id == doctorProfileId }
    }

    private fun clearLegacy(token: String, which: (com.shifa.doctors.domain.DoctorProfile) -> Boolean) {
        val stale = doctorProfiles.findByFcmToken(token).filter(which)
        if (stale.isEmpty()) return
        stale.forEach { it.fcmToken = null }
        doctorProfiles.saveAll(stale)
    }

    @Transactional(readOnly = true)
    fun tokensOf(doctorProfileId: Long): List<String> = repo.findByDoctorProfileId(doctorProfileId).map { it.token }

    /** FCM said the token is gone for good (any doctor). */
    @Transactional
    fun drop(token: String): Int = repo.deleteByToken(token)
}
