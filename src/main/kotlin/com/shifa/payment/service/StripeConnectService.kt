package com.shifa.payment.service

import com.shifa.config.StripeProperties
import com.shifa.domain.DoctorBilling
import com.shifa.repo.DoctorBillingRepository
import com.shifa.repo.DoctorProfileRepository
import com.stripe.exception.StripeException
import com.stripe.Stripe
import com.stripe.model.Account
import com.stripe.model.AccountLink
import com.stripe.param.AccountCreateParams
import com.stripe.param.AccountLinkCreateParams
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@Service
class StripeConnectService(
    private val stripeProperties: StripeProperties,
    private val doctorProfiles: DoctorProfileRepository,
    private val doctorBillingRepository: DoctorBillingRepository
) {
    data class ConnectOnboardingResult(
        val accountId: String,
        val onboardingUrl: String
    )

    @Transactional
    fun createOrResumeOnboarding(
        doctorId: Long,
        refreshUrl: String?,
        returnUrl: String?
    ): ConnectOnboardingResult {
        val apiKey = stripeProperties.secretKey.trim()
        if (apiKey.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe is not configured")
        }
        Stripe.apiKey = apiKey

        val doctor = doctorProfiles.findById(doctorId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found") }
        val billing = doctorBillingRepository.findByDoctorId(doctorId).orElse(DoctorBilling(doctor = doctor))

        val accountId = billing.stripeConnectAccountId?.takeIf { it.isNotBlank() } ?: run {
            val account = try {
                Account.create(
                    AccountCreateParams.builder()
                        .setType(AccountCreateParams.Type.EXPRESS)
                        .setCountry(if (doctor.locationCountry.equals("UZ", true)) "UZ" else "DE")
                        .setEmail(doctor.user.email ?: billing.billingEmail ?: "")
                        .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
                        .setCapabilities(
                            AccountCreateParams.Capabilities.builder()
                                .setTransfers(
                                    AccountCreateParams.Capabilities.Transfers.builder()
                                        .setRequested(true)
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
            } catch (e: StripeException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe Connect onboarding failed: ${e.message}", e)
            }
            billing.stripeConnectAccountId = account.id
            billing.updatedAt = OffsetDateTime.now()
            doctorBillingRepository.save(billing)
            account.id
        }

        val accountLink = try {
            AccountLink.create(
                AccountLinkCreateParams.builder()
                    .setAccount(accountId)
                    .setRefreshUrl(refreshUrl ?: stripeProperties.connectRefreshUrl)
                    .setReturnUrl(returnUrl ?: stripeProperties.connectReturnUrl)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .build()
            )
        } catch (e: StripeException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe Connect link creation failed: ${e.message}", e)
        }

        return ConnectOnboardingResult(accountId = accountId, onboardingUrl = accountLink.url)
    }
}
