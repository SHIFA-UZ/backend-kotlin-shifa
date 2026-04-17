package com.shifa.repo

import com.shifa.domain.Conversation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ConversationRepository : JpaRepository<Conversation, Long> {
    @Query(
        """
        SELECT c FROM Conversation c
        WHERE c.doctorUser.id = :doctorUserId
        OR (c.doctorParticipant.id = :doctorUserId AND c.doctorParticipant IS NOT NULL)
        ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
        """
    )
    fun findByDoctorUserIdOrderByLastMessageDesc(@Param("doctorUserId") doctorUserId: Long): List<Conversation>

    @Query(
        """
        SELECT c FROM Conversation c
        WHERE (
            (c.doctorUser.id = :doctorUserId AND (c.doctorParticipant.id = :participantId OR c.patientParticipant.id = :participantId))
            OR
            (c.doctorUser.id = :participantId AND c.doctorParticipant.id = :doctorUserId)
        )
        """
    )
    fun findByDoctorUserAndParticipant(
        @Param("doctorUserId") doctorUserId: Long,
        @Param("participantId") participantId: Long
    ): Conversation?

    @Query(
        """
        SELECT c FROM Conversation c
        WHERE c.patientParticipant.id = :patientId
        ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
        """
    )
    fun findByPatientParticipantIdOrderByLastMessageDesc(
        @Param("patientId") patientId: Long
    ): List<Conversation>

    @Query(
        """
        SELECT c FROM Conversation c
        WHERE c.doctorUser.id = :doctorUserId
        AND c.patientParticipant.id = :patientId
        """
    )
    fun findByDoctorUserAndPatient(
        @Param("doctorUserId") doctorUserId: Long,
        @Param("patientId") patientId: Long
    ): Conversation?

    /** All conversations where this user is the main doctor or the other doctor participant. */
    @Query(
        """
        SELECT c FROM Conversation c
        WHERE c.doctorUser.id = :userId OR c.doctorParticipant.id = :userId
        """
    )
    fun findByDoctorUserIdOrDoctorParticipantId(@Param("userId") userId: Long): List<Conversation>
}
