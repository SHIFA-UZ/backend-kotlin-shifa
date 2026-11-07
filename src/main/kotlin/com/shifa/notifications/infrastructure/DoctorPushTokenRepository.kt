package com.shifa.notifications.infrastructure

import com.shifa.notifications.domain.DoctorPushToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface DoctorPushTokenRepository : JpaRepository<DoctorPushToken, Long> {
    fun findByToken(token: String): DoctorPushToken?
    fun findByDoctorProfileId(doctorProfileId: Long): List<DoctorPushToken>

    @Modifying
    @Query("DELETE FROM DoctorPushToken t WHERE t.token = :token")
    fun deleteByToken(token: String): Int
}
