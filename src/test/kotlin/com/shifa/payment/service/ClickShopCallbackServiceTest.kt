package com.shifa.payment.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.ClickProperties
import com.shifa.payment.click.ClickPrepareRequest
import com.shifa.payment.repo.PaymentEventRepository
import com.shifa.payment.repo.PaymentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class ClickShopCallbackServiceTest {

    @Mock
    lateinit var paymentRepository: PaymentRepository

    @Mock
    lateinit var paymentService: PaymentService

    @Mock
    lateinit var paymentEventRepository: PaymentEventRepository

    @Mock
    lateinit var clickProperties: ClickProperties

    private lateinit var clickShopCallbackService: ClickShopCallbackService

    @BeforeEach
    fun setUp() {
        clickShopCallbackService =
            ClickShopCallbackService(
                paymentRepository,
                paymentService,
                paymentEventRepository,
                clickProperties,
                ObjectMapper()
            )
    }

    @Test
    fun `prepare rejects invalid sign before loading payment`() {
        `when`(clickProperties.serviceId).thenReturn(103L)
        `when`(clickProperties.secretKey).thenReturn("secret")

        val req =
            ClickPrepareRequest(
                clickTransId = 1L,
                serviceId = 103L,
                clickPaydocId = 2L,
                merchantTransId = "oid",
                amount = 100.0,
                action = 0,
                signTime = "t",
                signString = "not-a-valid-md5-hash-for-this-payloadxxxxxxxx"
            )

        val resp = clickShopCallbackService.handlePrepare(req)
        assertEquals(-1, resp.error)
        verify(paymentRepository, never()).findByExternalRef(anyString())
    }
}
