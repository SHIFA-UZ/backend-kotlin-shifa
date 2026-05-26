package com.shifa.payment.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.ClickProperties
import com.shifa.domain.Appointment
import com.shifa.payment.click.*
import com.shifa.payment.domain.Payment
import com.shifa.payment.domain.PaymentEvent
import com.shifa.payment.domain.PaymentGatewayCode
import com.shifa.payment.domain.PaymentKind
import com.shifa.payment.domain.PaymentStatus
import com.shifa.payment.repo.PaymentEventRepository
import com.shifa.payment.repo.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Incoming SHOP-API callbacks (Prepare / Complete) from Click.
 * Signature rules match Davlatov284/click-integration-spring-boot-starter [ClickSignatureUtil].
 */
@Service
class ClickShopCallbackService(
    private val paymentRepository: PaymentRepository,
    private val paymentService: PaymentService,
    private val paymentEventRepository: PaymentEventRepository,
    private val clickProperties: ClickProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(ClickShopCallbackService::class.java)

    @Transactional
    fun handlePrepare(request: ClickPrepareRequest): ClickPrepareResponse {
        touchEvent("CLICK_PREPARE", request.clickTransId, request)

        val validationError = validatePrepareIntegrity(request)
        if (validationError != null) {
            return rejectPrepare(request, validationError.first, validationError.second)
        }

        requireNotNull(request.clickTransId)
        requireNotNull(request.merchantTransId)

        if (request.serviceId != clickProperties.serviceId) {
            return rejectPrepare(request, ClickResponseCodes.CLIENT_NOT_FOUND, "Unknown service")
        }

        val expectedSign = ClickSignatureUtil.generatePrepareSignString(
            clickTransId = request.clickTransId,
            serviceId = request.serviceId!!,
            secretKey = clickProperties.secretKey,
            merchantTransId = request.merchantTransId,
            amount = request.amount!!,
            action = request.action!!,
            signTime = request.signTime!!
        )

        if (!expectedSign.equals(request.signString, ignoreCase = true)) {
            log.warn(
                "Click prepare SIGN_CHECK_FAILED merchantTransId={} clickTransId={}",
                request.merchantTransId,
                request.clickTransId
            )
            return rejectPrepare(request, ClickResponseCodes.SIGN_CHECK_FAILED, "SIGN CHECK FAILED!")
        }

        val payment =
            paymentRepository.findByExternalRef(request.merchantTransId!!)
                ?: return rejectPrepare(request, ClickResponseCodes.ORDER_NOT_FOUND, "Order does not exist")

        if (payment.kind != PaymentKind.CONSULTATION || payment.gateway != PaymentGatewayCode.CLICK) {
            return rejectPrepare(request, ClickResponseCodes.ORDER_NOT_FOUND, "Order does not exist")
        }

        val amountOk = amountsMatchUz(payment.amountMinor, request.amount)
        if (!amountOk) {
            return rejectPrepare(request, ClickResponseCodes.INCORRECT_AMOUNT, "Incorrect parameter amount")
        }

        if (payment.status == PaymentStatus.PAID) {
            return rejectPrepare(request, ClickResponseCodes.ALREADY_PAID, "Already paid")
        }

        if (payment.status != PaymentStatus.PENDING) {
            return rejectPrepare(request, ClickResponseCodes.ORDER_NOT_FOUND, "Order does not exist")
        }

        val clickIdStr = request.clickTransId!!.toString()
        val gp = payment.gatewayPaymentId?.trim().orEmpty()
        when {
            gp == clickIdStr -> Unit
            gp.isEmpty() || gp == payment.externalRef ->
                payment.gatewayPaymentId = clickIdStr

            else -> {
                log.warn(
                    "Click prepare trans mismatch paymentId={} existingGatewayPaymentId={} clickTransId={}",
                    payment.id,
                    gp,
                    clickIdStr
                )
                return rejectPrepare(request, ClickResponseCodes.TRANSACTION_NOT_FOUND, "Transaction does not exist")
            }
        }

        payment.updatedAt = Instant.now()
        paymentRepository.save(payment)

        log.info(
            "Click prepare OK paymentId={} clickTransId={} merchantTransId={}",
            payment.id,
            request.clickTransId,
            request.merchantTransId
        )

        return ClickPrepareResponse(
            clickTransId = request.clickTransId,
            merchantTransId = request.merchantTransId,
            merchantPrepareId = payment.id,
            error = ClickResponseCodes.SUCCESS,
            errorNote = "Success"
        )
    }

    @Transactional
    fun handleComplete(request: ClickCompleteRequest): ClickCompleteResponse {
        touchEvent("CLICK_COMPLETE", request.clickTransId, request)

        val validationError = validateCompleteIntegrity(request)
        if (validationError != null) {
            return rejectComplete(request, validationError.first, validationError.second)
        }

        requireNotNull(request.clickTransId)
        requireNotNull(request.merchantTransId)
        requireNotNull(request.merchantPrepareId)

        if (request.serviceId != clickProperties.serviceId) {
            return rejectComplete(request, ClickResponseCodes.CLIENT_NOT_FOUND, "Unknown service")
        }

        val expectedSign = ClickSignatureUtil.generateCompleteSignString(
            clickTransId = request.clickTransId,
            serviceId = request.serviceId!!,
            secretKey = clickProperties.secretKey,
            merchantTransId = request.merchantTransId,
            merchantPrepareId = request.merchantPrepareId!!,
            amount = request.amount!!,
            action = request.action!!,
            signTime = request.signTime!!
        )

        if (!expectedSign.equals(request.signString, ignoreCase = true)) {
            log.warn(
                "Click complete SIGN_CHECK_FAILED merchantTransId={} clickTransId={}",
                request.merchantTransId,
                request.clickTransId
            )
            return rejectComplete(request, ClickResponseCodes.SIGN_CHECK_FAILED, "SIGN CHECK FAILED!")
        }

        val payment =
            paymentRepository.findByExternalRef(request.merchantTransId!!)
                ?: return rejectComplete(request, ClickResponseCodes.ORDER_NOT_FOUND, "Order does not exist")

        if (payment.kind != PaymentKind.CONSULTATION || payment.gateway != PaymentGatewayCode.CLICK) {
            return rejectComplete(request, ClickResponseCodes.ORDER_NOT_FOUND, "Order does not exist")
        }

        if (payment.id != request.merchantPrepareId) {
            return rejectComplete(request, ClickResponseCodes.ORDER_NOT_FOUND, "Order does not exist")
        }

        val amountOk = amountsMatchUz(payment.amountMinor, request.amount)
        if (!amountOk) {
            return rejectComplete(request, ClickResponseCodes.INCORRECT_AMOUNT, "Incorrect parameter amount")
        }

        val clickIdStr = request.clickTransId!!.toString()
        if (payment.gatewayPaymentId?.trim().orEmpty() != clickIdStr) {
            return rejectComplete(request, ClickResponseCodes.TRANSACTION_NOT_FOUND, "Transaction does not exist")
        }

        if (request.error != null && request.error < 0) {
            log.info(
                "Click complete cancellation merchantTransId={} clickError={}",
                request.merchantTransId,
                request.error
            )
            paymentService.markConsultationPaymentCancelledFromClick(
                request.merchantTransId!!,
                request.errorNote ?: "Cancellation from Click (${request.error})"
            )
            return ClickCompleteResponse(
                clickTransId = request.clickTransId,
                merchantTransId = request.merchantTransId,
                merchantPrepareId = request.merchantPrepareId,
                merchantConfirmId = null,
                error = ClickResponseCodes.TRANSACTION_CANCELLED,
                errorNote = "Transaction cancelled"
            )
        }

        if (payment.status == PaymentStatus.PAID) {
            return ClickCompleteResponse(
                clickTransId = request.clickTransId,
                merchantTransId = request.merchantTransId,
                merchantPrepareId = request.merchantPrepareId,
                merchantConfirmId = payment.id,
                error = ClickResponseCodes.SUCCESS,
                errorNote = "Success"
            )
        }

        paymentService.markPaymentPaidByExternalRef(request.merchantTransId!!)
        log.info(
            "Click complete PAID paymentId={} merchantTransId={} clickTransId={}",
            payment.id,
            request.merchantTransId,
            request.clickTransId
        )

        return ClickCompleteResponse(
            clickTransId = request.clickTransId,
            merchantTransId = request.merchantTransId,
            merchantPrepareId = request.merchantPrepareId,
            merchantConfirmId = payment.id,
            error = ClickResponseCodes.SUCCESS,
            errorNote = "Success"
        )
    }

    private fun amountsMatchUz(amountMinor: Long, clickAmount: Double?): Boolean {
        if (clickAmount == null) return false
        return kotlin.math.abs(clickAmount - amountMinor.toDouble()) <= 0.01
    }

    private fun validatePrepareIntegrity(req: ClickPrepareRequest): Pair<Int, String>? {
        return when {
            req.clickTransId == null ||
                req.serviceId == null ||
                req.clickPaydocId == null ||
                req.amount == null ||
                req.action == null ||
                req.signTime.isNullOrBlank() ||
                req.signString.isNullOrBlank() ||
                req.merchantTransId.isNullOrBlank() ->
                ClickResponseCodes.ERROR_IN_REQUEST_FROM_CLICK to "Error in request from click"

            req.action != 0 -> ClickResponseCodes.ACTION_NOT_FOUND to "Action not found"
            else -> null
        }
    }

    private fun validateCompleteIntegrity(req: ClickCompleteRequest): Pair<Int, String>? {
        return when {
            req.clickTransId == null ||
                req.serviceId == null ||
                req.clickPaydocId == null ||
                req.amount == null ||
                req.action == null ||
                req.signTime.isNullOrBlank() ||
                req.signString.isNullOrBlank() ||
                req.merchantTransId.isNullOrBlank() ||
                req.merchantPrepareId == null ->
                ClickResponseCodes.ERROR_IN_REQUEST_FROM_CLICK to "Error in request from click"

            req.action != 1 -> ClickResponseCodes.ACTION_NOT_FOUND to "Action not found"
            else -> null
        }
    }

    private fun rejectPrepare(request: ClickPrepareRequest, code: Int, note: String): ClickPrepareResponse =
        ClickPrepareResponse(
            clickTransId = request.clickTransId,
            merchantTransId = request.merchantTransId,
            merchantPrepareId = null,
            error = code,
            errorNote = note
        )

    private fun rejectComplete(request: ClickCompleteRequest, code: Int, note: String): ClickCompleteResponse =
        ClickCompleteResponse(
            clickTransId = request.clickTransId,
            merchantTransId = request.merchantTransId,
            merchantPrepareId = request.merchantPrepareId,
            merchantConfirmId = null,
            error = code,
            errorNote = note
        )

    private fun touchEvent(suffix: String, clickTransId: Long?, body: Any) {
        val idPart = clickTransId?.toString() ?: "unknown"
        val eventId = "click:${suffix.lowercase().replace('_', '-')}:$idPart"
        if (paymentEventRepository.existsByGatewayAndEventId(PaymentGatewayCode.CLICK, eventId)) {
            return
        }
        paymentEventRepository.save(
            PaymentEvent(
                gateway = PaymentGatewayCode.CLICK,
                eventId = eventId,
                eventType = suffix,
                payload = safePayload(body),
                processed = true,
                processedAt = Instant.now()
            )
        )
    }

    private fun safePayload(body: Any): String =
        try {
            objectMapper.writeValueAsString(body)
        } catch (_: Exception) {
            body.toString()
        }
}

/**
 * Mirrors Click starter [integration.payment.clickintegration.model.ClickErrorCode] where relevant.
 */
object ClickResponseCodes {
    const val SUCCESS = 0
    const val SIGN_CHECK_FAILED = -1
    const val INCORRECT_AMOUNT = -2
    const val ACTION_NOT_FOUND = -3
    const val ALREADY_PAID = -4
    const val ORDER_NOT_FOUND = -5
    const val TRANSACTION_NOT_FOUND = -6
    const val ERROR_IN_REQUEST_FROM_CLICK = -8
    const val TRANSACTION_CANCELLED = -9

    /** Not in starter list; used when service_id does not match configuration. */
    const val CLIENT_NOT_FOUND = -5
}
