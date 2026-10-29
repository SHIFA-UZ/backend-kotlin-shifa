package com.shifa.auth.application

import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.infrastructure.UserActivityLogRepository
import com.shifa.auth.infrastructure.UserRepository
import com.shifa.auth.infrastructure.UserRoleRepository
import com.shifa.auth.infrastructure.UserSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * Permanently deletes a user and all related data so the phone number (and email) can be used to create a new account.
 * Does NOT delete ADMIN users (audit trail and safety).
 */
@Service
class UserDeletionService(
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val userSessionRepository: UserSessionRepository,
    private val userActivityLogRepository: UserActivityLogRepository,
    private val invitationKeyRepository: com.shifa.clinics.application.InvitationKeys,
    private val doctorProfileRepository: com.shifa.doctors.application.DoctorProfiles,
    private val patientProfileRepository: com.shifa.patients.application.PatientProfiles,
    private val appointmentRepository: com.shifa.appointments.application.Appointments,
    private val weeklyScheduleRuleRepository: com.shifa.appointments.application.WeeklyScheduleRules,
    private val dateSpecificScheduleRuleRepository: com.shifa.appointments.application.DateSpecificScheduleRules,
    private val scheduleBlockRepository: com.shifa.appointments.application.ScheduleBlocks,
    private val scheduleValidityPeriodRepository: com.shifa.appointments.application.ScheduleValidityPeriods,
    private val doctorReviewRepository: com.shifa.doctors.application.DoctorReviews,
    private val notificationService: com.shifa.notifications.application.NotificationService,
    private val remoteCareTaskRepository: com.shifa.tasks.application.RemoteCareTasks,
    private val conversationRepository: com.shifa.chat.application.Conversations,
    private val messageRepository: com.shifa.chat.application.Messages,
    private val patientDocumentService: com.shifa.documents.application.PatientDocumentService,
    private val patientFormRepository: com.shifa.patients.application.PatientForms,
    private val documentAccessService: com.shifa.documents.application.DocumentAccessService,
    private val consultationNoteRepository: com.shifa.appointments.application.ConsultationNotes,
    private val aiDraftNoteRepository: com.shifa.ai.application.AiDraftNotes,
    private val doctorSettingsRepository: com.shifa.doctors.application.DoctorSettingsDoor,
    private val doctorBillingService: com.shifa.payments.application.DoctorBillingService,
) {
    private val log = LoggerFactory.getLogger(UserDeletionService::class.java)

    /**
     * Permanently delete the user and all related data. The phone (and email) can then be used to register again.
     * @param userId User id (users.id)
     * @throws ResponseStatusException BAD_REQUEST if user is ADMIN, NOT_FOUND if user does not exist
     */
    @Transactional
    fun deleteUser(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: $userId") }

        if (user.role == Role.ADMIN) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete admin users. Deactivate instead.")
        }

        log.info("USER_DELETION: Starting full deletion for userId={}, role={}", userId, user.role)

        // 1. Sessions and activity
        val sessions = userSessionRepository.findByUserIdOrderByCreatedAtDesc(userId)
        userSessionRepository.deleteAll(sessions)
        userActivityLogRepository.deleteByUserId(userId)

        // 2. Invitation keys: null references so we can delete the user
        invitationKeyRepository.nullifyConsumedByUserId(userId)
        invitationKeyRepository.nullifyCreatedByUserId(userId)

        // 3. Doctor-related data (user may have doctor profile)
        doctorProfileRepository.findByUserId(userId).ifPresent { doctor ->
            val doctorId = doctor.id
            appointmentRepository.deleteByDoctorId(doctorId)
            weeklyScheduleRuleRepository.deleteByDoctorId(doctorId)
            dateSpecificScheduleRuleRepository.deleteByDoctorId(doctorId)
            scheduleBlockRepository.deleteByDoctorId(doctorId)
            scheduleValidityPeriodRepository.findByDoctorIdOrderByValidFromAsc(doctorId).let {
                scheduleValidityPeriodRepository.deleteAll(it)
            }
            doctorReviewRepository.findByDoctorId(doctorId).let { doctorReviewRepository.deleteAll(it) }
            notificationService.deleteAllForDoctor(doctorId)
            remoteCareTaskRepository.findByDoctorIdOrderByCreatedAtDesc(doctorId).let { remoteCareTaskRepository.deleteAll(it) }
            val doctorConvs = conversationRepository.findByDoctorUserIdOrDoctorParticipantId(userId)
            if (doctorConvs.isNotEmpty()) {
                messageRepository.deleteByConversationIdIn(doctorConvs.mapNotNull { it.id })
                conversationRepository.deleteAll(doctorConvs)
            }
            consultationNoteRepository.findByDoctorId(doctorId).let { consultationNoteRepository.deleteAll(it) }
            aiDraftNoteRepository.findByDoctorId(doctorId).let { aiDraftNoteRepository.deleteAll(it) }
            documentAccessService.deleteAccessDataForDoctor(doctorId)
            doctorSettingsRepository.findByDoctorId(doctorId).ifPresent { doctorSettingsRepository.delete(it) }
            doctorBillingService.deleteForDoctor(doctorId)
            // Patient rows may reference this doctor as creator or document uploader; clear FKs before deleting profile.
            patientProfileRepository.clearCreatedByDoctor(doctorId)
            patientDocumentService.clearUploadedByDoctor(doctorId)
            doctorProfileRepository.delete(doctor)
        }

        // 4. Patient-related data (user may have patient profile)
        patientProfileRepository.findByUserId(userId).ifPresent { patient ->
            val patientId = patient.id!!
            appointmentRepository.deleteByPatientId(patientId)
            notificationService.deleteAllForPatient(patientId)
            remoteCareTaskRepository.findByPatientIdOrderByCreatedAtDesc(patientId).let { remoteCareTaskRepository.deleteAll(it) }
            doctorReviewRepository.findByPatientId(patientId).let { doctorReviewRepository.deleteAll(it) }
            val patientConvs = conversationRepository.findByPatientParticipantIdOrderByLastMessageDesc(patientId)
            if (patientConvs.isNotEmpty()) {
                messageRepository.deleteByConversationIdIn(patientConvs.mapNotNull { it.id })
                conversationRepository.deleteAll(patientConvs)
            }
            documentAccessService.deleteAccessDataForPatient(patientId)
            patientDocumentService.deleteAllForPatient(patientId)
            patientFormRepository.findByPatientIdOrderByDateDesc(patientId).let { patientFormRepository.deleteAll(it) }
            consultationNoteRepository.findByPatientId(patientId).let { consultationNoteRepository.deleteAll(it) }
            aiDraftNoteRepository.findByPatientId(patientId).let { aiDraftNoteRepository.deleteAll(it) }
            patientProfileRepository.delete(patient)
        }

        // 5. User roles and user
        userRoleRepository.findByUserId(userId).let { userRoleRepository.deleteAll(it) }
        userRepository.delete(user)

        log.info("USER_DELETION: Completed for userId={}. User and all related data removed.", userId)
    }
}
