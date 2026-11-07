package com.shifa.chat.api

import com.shifa.auth.domain.User
import com.shifa.chat.application.MessageService
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.shared.security.DoctorPrincipal
import java.time.Instant
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** BE-14: the conversation's after/limit and the list's since reach the service; without them, as before. */
@WebMvcTest(
    controllers = [MessageController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [
                com.shifa.shared.security.AiRateLimitFilter::class,
                com.shifa.shared.security.JwtAuthFilter::class,
                com.shifa.shared.security.UserRateLimitFilter::class,
            ],
        ),
    ],
)
@AutoConfigureMockMvc(addFilters = false)
@Import(com.shifa.shared.config.CacheConfig::class, com.shifa.shared.security.AuthenticationPrincipalTestConfig::class)
class MessageCursorParamsTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockitoBean lateinit var messages: MessageService

    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "Aziza", lastName = "Karimova")
    private val principal = DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
    private val conversation = ConversationDto(5, "Franco", null, false, 7, "hi", null, 0)

    @BeforeEach
    fun signIn() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    @Test
    fun `after and limit are passed on`() {
        `when`(messages.getConversationWithMessages(5L, 2L, 120L, 50)).thenReturn(ConversationWithMessagesDto(conversation, emptyList()))

        mockMvc.perform(get("/api/messages/conversations/5").param("after", "120").param("limit", "50"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.conversation.id").value(5))
        verify(messages).getConversationWithMessages(5L, 2L, 120L, 50)
    }

    @Test
    fun `without params the conversation is read as before`() {
        `when`(messages.getConversationWithMessages(5L, 2L, null, null)).thenReturn(ConversationWithMessagesDto(conversation, emptyList()))

        mockMvc.perform(get("/api/messages/conversations/5")).andExpect(status().isOk)
        verify(messages).getConversationWithMessages(5L, 2L, null, null)
    }

    @Test
    fun `since is an ISO instant for the list`() {
        `when`(messages.listConversations(2L, Instant.parse("2026-11-07T08:00:00Z"))).thenReturn(listOf(conversation))

        mockMvc.perform(get("/api/messages/conversations").param("since", "2026-11-07T08:00:00Z"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(5))
        verify(messages).listConversations(2L, Instant.parse("2026-11-07T08:00:00Z"))
    }

    @Test
    fun `a since that is not an instant is a bad request`() {
        mockMvc.perform(get("/api/messages/conversations").param("since", "yesterday")).andExpect(status().isBadRequest)
    }
}
