package com.shifa.service

import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.repo.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*

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
    private val invitationKeyRepository: InvitationKeyRepository,
    private val doctorProfileRepository: DoctorProfileRepository,
    private val patientProfileRepository: PatientProfileRepository,
    private val appointmentRepository: AppointmentRepository,
    private val weeklyScheduleRuleRepository: WeeklyScheduleRuleRepository,
    private val dateSpecificScheduleRuleRepository: DateSpecificScheduleRuleRepository,
    private val scheduleValidityPeriodRepository: ScheduleValidityPeriodRepository,
    private val doctorReviewRepository: DoctorReviewRepository,
    private val notificationRepository: NotificationRepository,
    private val remoteCareTaskRepository: RemoteCareTaskRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val patientDocumentRepository: PatientDocumentRepository,
    private val patientFormRepository: PatientFormRepository,
    private val documentAccessRequestRepository: DocumentAccessRequestRepository,
    private val documentAccessGrantRepository: DocumentAccessGrantRepository,
    private val consultationNoteRepository: ConsultationNoteRepository,
    private val aiDraftNoteRepository: AiDraftNoteRepository,
    private val doctorSettingsRepository: DoctorSettingsRepository,
    private val doctorBillingRepository: DoctorBillingRepository,
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

        log.info("USER_DELETION: Starting full deletion for userId={}, role={}, phone={}", userId, user.role, user.phone)

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
            scheduleValidityPeriodRepository.findByDoctorIdOrderByValidFromAsc(doctorId).let {
                scheduleValidityPeriodRepository.deleteAll(it)
            }
            doctorReviewRepository.findByDoctorId(doctorId).let { doctorReviewRepository.deleteAll(it) }
            notificationRepository.findByDoctor_IdOrderByCreatedAtDesc(doctorId).let { notificationRepository.deleteAll(it) }
            remoteCareTaskRepository.findByDoctorIdOrderByCreatedAtDesc(doctorId).let { remoteCareTaskRepository.deleteAll(it) }
            val doctorConvs = conversationRepository.findByDoctorUserIdOrDoctorParticipantId(userId)
            if (doctorConvs.isNotEmpty()) {
                messageRepository.deleteByConversationIdIn(doctorConvs.mapNotNull { it.id })
                conversationRepository.deleteAll(doctorConvs)
            }
            consultationNoteRepository.findByDoctorId(doctorId).let { consultationNoteRepository.deleteAll(it) }
            aiDraftNoteRepository.findByDoctorId(doctorId).let { aiDraftNoteRepository.deleteAll(it) }
            documentAccessRequestRepository.findByRequestingDoctor_Id(doctorId).let { documentAccessRequestRepository.deleteAll(it) }
            documentAccessGrantRepository.findByDoctor_Id(doctorId).let { documentAccessGrantRepository.deleteAll(it) }
            doctorSettingsRepository.findByDoctorId(doctorId).ifPresent { doctorSettingsRepository.delete(it) }
            doctorBillingRepository.findByDoctorId(doctorId).ifPresent { doctorBillingRepository.delete(it) }
            // Patient rows may reference this doctor as creator or document uploader; clear FKs before deleting profile.
            patientProfileRepository.clearCreatedByDoctor(doctorId)
            patientDocumentRepository.clearUploadedByDoctor(doctorId)
            doctorProfileRepository.delete(doctor)
        }

        // 4. Patient-related data (user may have patient profile)
        patientProfileRepository.findByUserId(userId).ifPresent { patient ->
            val patientId = patient.id!!
            appointmentRepository.deleteByPatientId(patientId)
            notificationRepository.findByPatient_IdOrderByCreatedAtDesc(patientId).let { notificationRepository.deleteAll(it) }
            remoteCareTaskRepository.findByPatientIdOrderByCreatedAtDesc(patientId).let { remoteCareTaskRepository.deleteAll(it) }
            doctorReviewRepository.findByPatientId(patientId).let { doctorReviewRepository.deleteAll(it) }
            val patientConvs = conversationRepository.findByPatientParticipantIdOrderByLastMessageDesc(patientId)
            if (patientConvs.isNotEmpty()) {
                messageRepository.deleteByConversationIdIn(patientConvs.mapNotNull { it.id })
                conversationRepository.deleteAll(patientConvs)
            }
            documentAccessRequestRepository.findByDocument_Patient_Id(patientId).let { documentAccessRequestRepository.deleteAll(it) }
            documentAccessGrantRepository.findByDocument_Patient_Id(patientId).let { documentAccessGrantRepository.deleteAll(it) }
            patientDocumentRepository.listForPatient(patientId).let { patientDocumentRepository.deleteAll(it) }
            patientFormRepository.findByPatientIdOrderByDateDesc(patientId).let { patientFormRepository.deleteAll(it) }
            consultationNoteRepository.findByPatientId(patientId).let { consultationNoteRepository.deleteAll(it) }
            aiDraftNoteRepository.findByPatientId(patientId).let { aiDraftNoteRepository.deleteAll(it) }
            patientProfileRepository.delete(patient)
        }

        // 5. User roles and user
        userRoleRepository.findByUserId(userId).let { userRoleRepository.deleteAll(it) }
        userRepository.delete(user)

        log.info("USER_DELETION: Completed for userId={}, phone={}. User and all related data removed.", userId, user.phone)
    }
}
