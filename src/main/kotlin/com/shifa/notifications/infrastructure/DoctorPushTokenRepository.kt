package com.shifa.notifications.infrastructure

import com.shifa.notifications.domain.DoctorPushToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface DoctorPushTokenRepository : JpaRepository<DoctorPushToken, Long> {
    fun findByToken(token: String): DoctorPushToken?
    fun findByDoctorProfileId(doctorProfileId: Long): List<DoctorPushToken>

    /**
     * The doctor's device tokens whose session is still live. A row without a session (registered before V132) counts
     * while the doctor has any live session.
     */
    @Query(
        value = """
            SELECT t.token FROM doctor_push_tokens t
            WHERE t.doctor_profile_id = :doctorProfileId AND EXISTS (
                SELECT 1 FROM user_sessions s
                WHERE s.revoked = false AND s.expires_at > now() AND (
                    s.token_jti = t.session_jti
                    OR (t.session_jti IS NULL AND s.user_id = (SELECT d.user_id FROM doctor_profiles d WHERE d.id = t.doctor_profile_id))
                )
            )
        """,
        nativeQuery = true,
    )
    fun findLiveTokens(doctorProfileId: Long): List<String>

    /** Whether the doctor is signed in anywhere (gates the legacy single token of the Flutter build). */
    @Query(
        value = """
            SELECT EXISTS (
                SELECT 1 FROM user_sessions s JOIN doctor_profiles d ON d.user_id = s.user_id
                WHERE d.id = :doctorProfileId AND s.revoked = false AND s.expires_at > now()
            )
        """,
        nativeQuery = true,
    )
    fun doctorHasLiveSession(doctorProfileId: Long): Boolean

    @Modifying
    @Query("DELETE FROM DoctorPushToken t WHERE t.token = :token")
    fun deleteByToken(token: String): Int
}
