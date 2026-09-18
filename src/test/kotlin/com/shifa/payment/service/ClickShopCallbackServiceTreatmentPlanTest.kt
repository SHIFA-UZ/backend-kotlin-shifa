package com.shifa.payment.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.ClickProperties
import com.shifa.payment.click.ClickCompleteRequest
import com.shifa.payment.click.ClickPrepareRequest
import com.shifa.payment.click.ClickSignatureUtil
import com.shifa.payment.domain.Payment
import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.domain.PaymentKind
import com.shifa.payment.domain.PaymentStatus
import com.shifa.payment.repo.PaymentEventRepository
import com.shifa.payment.repo.PaymentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClickShopCallbackServiceTreatmentPlanTest {

    @Mock
    lateinit var paymentRepository: PaymentRepository

    @Mock
    lateinit var paymentService: PaymentService

    @Mock
    lateinit var paymentEventRepository: PaymentEventRepository

    @Mock
    lateinit var clickProperties: ClickProperties

    private lateinit var clickShopCallbackService: ClickShopCallbackService

    private val secret = "secret"
    private val serviceId = 111453L

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
        `when`(clickProperties.serviceId).thenReturn(serviceId)
        `when`(clickProperties.secretKey).thenReturn(secret)
        // Default mock: existsByGatewayAndEventId -> false; save returns null (ignored).
        `when`(paymentRepository.save(any())).thenAnswer { it.arguments[0] }
    }

    private fun treatmentPlanPayment(
        amountMinor: Long = 5_000_000L,
        status: PaymentStatus = PaymentStatus.PENDING,
    ): Payment =
        Payment(
            id = 42L,
            externalRef = "tp-ref-1",
            gateway = PaymentGatewayCode.CLICK,
            kind = PaymentKind.TREATMENT_PLAN,
            status = status,
            amountMinor = amountMinor,
            currency = "UZS",
            gatewayPaymentId = "tp-ref-1",
        )

    @Test
    fun `prepare accepts TREATMENT_PLAN with soums amount`() {
        val payment = treatmentPlanPayment()
        `when`(paymentRepository.findByExternalRef("tp-ref-1")).thenReturn(payment)
        `when`(paymentService.treatmentPlanAmountStillPayable(payment)).thenReturn(true)

        val clickTransId = 99L
        val amountSoums = 50000.0
        val signTime = "2026-01-01 12:00:00"
        val sign = ClickSignatureUtil.generatePrepareSignString(
            clickTransId = clickTransId,
            serviceId = serviceId,
            secretKey = secret,
            merchantTransId = "tp-ref-1",
            amount = amountSoums,
            action = 0,
            signTime = signTime,
        )
        val req = ClickPrepareRequest(
            clickTransId = clickTransId,
            serviceId = serviceId,
            clickPaydocId = 1L,
            merchantTransId = "tp-ref-1",
            amount = amountSoums,
            action = 0,
            signTime = signTime,
            signString = sign,
        )

        val resp = clickShopCallbackService.handlePrepare(req)
        assertEquals(0, resp.error)
        assertEquals(42L, resp.merchantPrepareId)
    }

    @Test
    fun `prepare rejects TREATMENT_PLAN when balance no longer covers amount`() {
        val payment = treatmentPlanPayment()
        `when`(paymentRepository.findByExternalRef("tp-ref-1")).thenReturn(payment)
        `when`(paymentService.treatmentPlanAmountStillPayable(payment)).thenReturn(false)

        val clickTransId = 99L
        val amountSoums = 50000.0
        val signTime = "2026-01-01 12:00:00"
        val sign = ClickSignatureUtil.generatePrepareSignString(
            clickTransId = clickTransId,
            serviceId = serviceId,
            secretKey = secret,
            merchantTransId = "tp-ref-1",
            amount = amountSoums,
            action = 0,
            signTime = signTime,
        )
        val req = ClickPrepareRequest(
            clickTransId = clickTransId,
            serviceId = serviceId,
            clickPaydocId = 1L,
            merchantTransId = "tp-ref-1",
            amount = amountSoums,
            action = 0,
            signTime = signTime,
            signString = sign,
        )

        val resp = clickShopCallbackService.handlePrepare(req)
        assertEquals(-2, resp.error)
        verify(paymentService, never()).markPaymentPaidByExternalRef("tp-ref-1")
    }

    @Test
    fun `complete marks TREATMENT_PLAN paid via paymentService`() {
        val payment = treatmentPlanPayment(status = PaymentStatus.PENDING).also {
            it.gatewayPaymentId = "77"
        }
        `when`(paymentRepository.findByExternalRef("tp-ref-1")).thenReturn(payment)

        val clickTransId = 77L
        val amountSoums = 50000.0
        val signTime = "2026-01-01 12:00:00"
        val sign = ClickSignatureUtil.generateCompleteSignString(
            clickTransId = clickTransId,
            serviceId = serviceId,
            secretKey = secret,
            merchantTransId = "tp-ref-1",
            merchantPrepareId = 42L,
            amount = amountSoums,
            action = 1,
            signTime = signTime,
        )
        val req = ClickCompleteRequest(
            clickTransId = clickTransId,
            serviceId = serviceId,
            clickPaydocId = 1L,
            merchantTransId = "tp-ref-1",
            merchantPrepareId = 42L,
            amount = amountSoums,
            action = 1,
            error = 0,
            errorNote = "Success",
            signTime = signTime,
            signString = sign,
        )

        val resp = clickShopCallbackService.handleComplete(req)
        assertEquals(0, resp.error)
        verify(paymentService).markPaymentPaidByExternalRef("tp-ref-1")
    }
}
