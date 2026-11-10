package com.shifa.payments.api

import com.shifa.admin.application.AuditService
import com.shifa.admin.domain.AdminProfile
import com.shifa.admin.domain.AuditLog
import com.shifa.admin.infrastructure.AdminProfileRepository
import com.shifa.admin.infrastructure.AuditLogRepository
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.payments.application.PaymentAdminQueries
import com.shifa.payments.application.StripeWebhookService
import com.shifa.shared.security.AdminPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest

class AdminPaymentControllerTest {
    private val stripe = mock(StripeWebhookService::class.java)
    private val auditLogs = mock(AuditLogRepository::class.java)
    private val controller = AdminPaymentController(
        mock(PaymentAdminQueries::class.java),
        stripe,
        AuditService(auditLogs, mock(AdminProfileRepository::class.java)),
    )
    private val admin = AdminPrincipal(
        AdminProfile(user = User(id = 5, passwordHash = "x", role = Role.ADMIN), firstName = "A", lastName = "B"),
        emptyList(),
    )

    @Test
    fun `a webhook retry leaves an audit row with its outcome`() {
        `when`(stripe.retryStoredWebhookEvent(7, 5)).thenReturn(false)
        val request = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.7"
            addHeader("User-Agent", "panel")
        }

        assertEquals(mapOf("success" to false, "paymentEventId" to 7L), controller.retryFailedStripeWebhook(admin, 7, request))

        val saved = ArgumentCaptor.forClass(AuditLog::class.java)
        verify(auditLogs).saveAndFlush(saved.capture())
        with(saved.value) {
            assertEquals("STRIPE_WEBHOOK_RETRIED", actionType)
            assertEquals("PAYMENT_EVENT", entityType)
            assertEquals(7L, entityId)
            assertEquals(mapOf("success" to false), details)
            assertEquals(5L, adminUser.id)
            assertEquals("203.0.113.7", ipAddress)
            assertEquals("panel", userAgent)
        }
    }
}
