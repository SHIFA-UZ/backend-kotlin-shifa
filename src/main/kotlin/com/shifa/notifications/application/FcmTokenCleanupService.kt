package com.shifa.notifications.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Drops FCM tokens that Firebase will never accept again (wrong project / unregistered device)
 * so schedulers stop retrying them every hour.
 */
@Service
class FcmTokenCleanupService(
    private val patientProfiles: com.shifa.patients.application.PatientProfiles,
    private val doctorProfiles: com.shifa.doctors.application.DoctorProfiles,
) {
    private val log = LoggerFactory.getLogger(FcmTokenCleanupService::class.java)

    @Transactional
    fun clearToken(token: String) {
        if (token.isBlank()) return
        val prefix = token.take(10)
        val patients = patientProfiles.findByFcmToken(token)
        if (patients.isNotEmpty()) {
            patients.forEach { it.fcmToken = null }
            patientProfiles.saveAll(patients)
            log.warn(
                "Cleared invalid FCM token from {} patient profile(s) (tokenPrefix={})",
                patients.size,
                prefix,
            )
        }
        val doctors = doctorProfiles.findByFcmToken(token)
        if (doctors.isNotEmpty()) {
            doctors.forEach { it.fcmToken = null }
            doctorProfiles.saveAll(doctors)
            log.warn(
                "Cleared invalid FCM token from {} doctor profile(s) (tokenPrefix={})",
                doctors.size,
                prefix,
            )
        }
    }
}
