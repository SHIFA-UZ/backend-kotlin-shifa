package com.shifa.payments.application

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.time.Duration

/** Where a doctor's Stripe Connect account stands (BE-18), for `billing.stripeConnectStatus` on `GET /doctors/me`. */
enum class StripeConnectStatus { NOT_CONNECTED, ONBOARDING, ACTIVE, RESTRICTED }

/** The flags Stripe reports for a Connect account (`details_submitted`, `charges_enabled`, transfers, `disabled_reason`). */
data class StripeConnectAccountFlags(
    val detailsSubmitted: Boolean,
    val chargesEnabled: Boolean,
    val transfersActive: Boolean,
    val disabledReason: String?,
)

/** Reads a Connect account from Stripe (`payments.infrastructure.StripeConnectAccountClient`). Read only. */
interface StripeConnectAccounts {
    /** Null when Stripe is not configured or can't be asked right now. */
    fun flags(accountId: String): StripeConnectAccountFlags?
}

/**
 * Read only: never creates, changes or charges anything. Answers are cached 5 minutes per account so `GET /doctors/me`
 * asks Stripe at most that often; null (unknown) when Stripe can't be reached.
 */
@Service
class StripeConnectStatusService(private val accounts: StripeConnectAccounts) {
    private val cache: Cache<String, StripeConnectStatus> =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(10_000).build()

    fun status(accountId: String?): StripeConnectStatus? {
        val id = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return StripeConnectStatus.NOT_CONNECTED
        cache.getIfPresent(id)?.let { return it }
        return accounts.flags(id)?.let { statusOf(it) }?.also { cache.put(id, it) }
    }

    companion object {
        /**
         * Express accounts here request only `transfers` (destination charges), so a finished account may have
         * `charges_enabled` false; active transfers count as ACTIVE too.
         */
        fun statusOf(flags: StripeConnectAccountFlags): StripeConnectStatus = when {
            !flags.detailsSubmitted -> StripeConnectStatus.ONBOARDING
            flags.disabledReason == null && (flags.chargesEnabled || flags.transfersActive) -> StripeConnectStatus.ACTIVE
            else -> StripeConnectStatus.RESTRICTED
        }
    }
}
