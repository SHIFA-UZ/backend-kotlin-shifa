package com.shifa.payments.application

import com.shifa.auth.application.Users
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.SubscriptionTier
import com.shifa.auth.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/** A paid Stripe plan sets the doctor's tier; it never takes away what an admin granted. */
class SubscriptionTierServiceTest {
    private val service = SubscriptionTierService(mock(Users::class.java))

    private fun doctor(tier: SubscriptionTier) =
        User(passwordHash = "x", role = Role.DOCTOR, subscriptionTier = tier)

    @Test
    fun `a paid plan raises the tier, never lowers it`() {
        val basic = doctor(SubscriptionTier.BASIC)
        service.grantPaidTier(basic, "PRO")
        assertEquals(SubscriptionTier.PRO, basic.subscriptionTier)

        val premium = doctor(SubscriptionTier.PREMIUM) // granted by an admin
        service.grantPaidTier(premium, "PRO")
        assertEquals(SubscriptionTier.PREMIUM, premium.subscriptionTier)

        val other = doctor(SubscriptionTier.BASIC)
        service.grantPaidTier(other, "CLINIC_BUNDLE") // not a tier's name
        assertEquals(SubscriptionTier.BASIC, other.subscriptionTier)
    }

    @Test
    fun `an ended plan goes back to BASIC unless an admin changed the tier since`() {
        val pro = doctor(SubscriptionTier.PRO)
        service.endPaidTier(pro, "PRO")
        assertEquals(SubscriptionTier.BASIC, pro.subscriptionTier)

        val raised = doctor(SubscriptionTier.PREMIUM)
        service.endPaidTier(raised, "PRO")
        assertEquals(SubscriptionTier.PREMIUM, raised.subscriptionTier)
    }
}
