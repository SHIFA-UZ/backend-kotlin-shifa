package com.shifa.payments.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** BE-18: the Connect status from Stripe's flags; read only, cached, unknown when Stripe can't be asked. */
class StripeConnectStatusServiceTest {
    private fun flags(details: Boolean, charges: Boolean = false, transfers: Boolean = false, disabled: String? = null) =
        StripeConnectAccountFlags(details, charges, transfers, disabled)

    @Test
    fun `flags map to the four states`() {
        assertEquals(StripeConnectStatus.ONBOARDING, StripeConnectStatusService.statusOf(flags(details = false)))
        assertEquals(StripeConnectStatus.ACTIVE, StripeConnectStatusService.statusOf(flags(details = true, charges = true)))
        assertEquals(StripeConnectStatus.ACTIVE, StripeConnectStatusService.statusOf(flags(details = true, transfers = true)))
        assertEquals(StripeConnectStatus.RESTRICTED, StripeConnectStatusService.statusOf(flags(details = true)))
        assertEquals(
            StripeConnectStatus.RESTRICTED,
            StripeConnectStatusService.statusOf(flags(details = true, charges = true, disabled = "requirements.past_due")),
        )
    }

    @Test
    fun `no account needs no call, answers are cached, failures are unknown`() {
        var calls = 0
        var answer: StripeConnectAccountFlags? = flags(details = true, transfers = true)
        val service = StripeConnectStatusService(object : StripeConnectAccounts {
            override fun flags(accountId: String): StripeConnectAccountFlags? = answer.also { calls++ }
        })
        assertEquals(StripeConnectStatus.NOT_CONNECTED, service.status(null))
        assertEquals(StripeConnectStatus.NOT_CONNECTED, service.status(" "))
        assertEquals(0, calls)
        assertEquals(StripeConnectStatus.ACTIVE, service.status("acct_1"))
        assertEquals(StripeConnectStatus.ACTIVE, service.status("acct_1"))
        assertEquals(1, calls)
        answer = null
        assertNull(service.status("acct_2"))
    }
}
