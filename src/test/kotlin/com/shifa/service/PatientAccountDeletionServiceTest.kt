package com.shifa.service

import com.shifa.config.AppProperties
import com.shifa.domain.Conversation
import com.shifa.domain.Message
import com.shifa.domain.PatientProfile
import com.shifa.domain.Role
import com.shifa.domain.User
import com.shifa.repo.ConversationRepository
import com.shifa.repo.MessageRepository
import com.shifa.repo.PatientProfileRepository
import com.shifa.repo.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.Optional

class PatientAccountDeletionServiceTest {
    @Test
    fun `deletePatientAccount revokes sessions deletes chat and clears phone for reuse`() {
        val users = mock(UserRepository::class.java)
        val userManagement = mock(UserManagementService::class.java)
        val patientProfiles = mock(PatientProfileRepository::class.java)
        val conversations = mock(ConversationRepository::class.java)
        val messages = mock(MessageRepository::class.java)
        val appProps = AppProperties().apply {
            storageRoot = "."
            publicBaseUrl = "http://localhost"
        }

        val svc = PatientAccountDeletionService(
            users = users,
            userManagementService = userManagement,
            patientProfiles = patientProfiles,
            conversations = conversations,
            messages = messages,
            appProperties = appProps
        )

        val userId = 123L
        val user = User(
            id = userId,
            email = "patient@test.com",
            phone = "+998901234567",
            username = "u",
            passwordHash = "hash",
            role = Role.PATIENT,
            enabled = true
        )

        val profile = PatientProfile(
            id = 42L,
            fullName = "John Doe",
            phone = "+998901234567",
            phoneNormalized = "+998901234567",
            email = "patient@test.com",
            address = "Addr",
        ).apply { this.user = user }

        val conv = Conversation(id = 1L, doctorUser = user, patientParticipant = profile)
        val msg = Message(
            id = 1L,
            conversation = conv,
            senderUser = user,
            recipientPatient = profile,
            attachmentUrl = "http://localhost/chat-attachments/a.png",
            thumbnailUrl = "http://localhost/chat-attachments/thumb_a.png"
        )

        `when`(users.findById(userId)).thenReturn(Optional.of(user))
        `when`(patientProfiles.findByUserId(userId)).thenReturn(Optional.of(profile))
        `when`(conversations.findByPatientParticipantIdOrderByLastMessageDesc(42L)).thenReturn(listOf(conv))
        `when`(messages.findByConversationIdOrderByCreatedAtAsc(1L)).thenReturn(listOf(msg))
        `when`(users.save(any(User::class.java))).thenAnswer { it.arguments[0] }
        `when`(patientProfiles.save(any(PatientProfile::class.java))).thenAnswer { it.arguments[0] }

        svc.deletePatientAccount(userId)

        verify(userManagement, times(1)).forceLogout(userId, null)
        verify(messages, times(1)).deleteByConversationIdIn(listOf(1L))
        verify(conversations, times(1)).deleteAll(listOf(conv))

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(users).save(userCaptor.capture())
        val savedUser = userCaptor.value
        assertFalse(savedUser.enabled)
        assertEquals(User.AccountStatus.DELETED, savedUser.accountStatus)
        assertNull(savedUser.phone)
        assertNull(savedUser.email)

        val profileCaptor = ArgumentCaptor.forClass(PatientProfile::class.java)
        verify(patientProfiles).save(profileCaptor.capture())
        val savedProfile = profileCaptor.value
        assertEquals("Deleted User", savedProfile.fullName)
        assertNull(savedProfile.phone)
        assertNull(savedProfile.phoneNormalized)
        assertNull(savedProfile.email)
        assertNull(savedProfile.address)
    }
}

