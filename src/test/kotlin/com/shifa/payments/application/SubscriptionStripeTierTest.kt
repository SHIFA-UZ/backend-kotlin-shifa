package com.shifa.payments.application

import com.shifa.auth.application.Users
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.SubscriptionTier
import com.shifa.auth.domain.User
import com.shifa.doctors.application.DoctorProfiles
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.payments.domain.DoctorSubscription
import com.shifa.payments.domain.PaymentGatewayCode
import com.shifa.payments.domain.SubscriptionInterval
import com.shifa.payments.domain.SubscriptionPlan
import com.shifa.payments.infrastructure.DoctorSubscriptionRepository
import com.shifa.payments.infrastructure.SubscriptionPlanRepository
import com.shifa.shared.config.AppProperties
import com.shifa.shared.config.StripeProperties
import com.stripe.model.Subscription
import com.stripe.model.checkout.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

/** The Stripe webhooks move the doctor's tier with their paid plan (checkout, plan change, cancellation). */
class SubscriptionStripeTierTest {
    private val user = User(passwordHash = "x", role = Role.DOCTOR, subscriptionTier = SubscriptionTier.BASIC)
    private val doctor = DoctorProfile(id = 4, user = user, firstName = "Ali", lastName = "Valiyev")
    private val plans =
        listOf("BASIC", "PRO").associateWith {
            SubscriptionPlan(code = it, name = it, priceMinor = 100, currency = "EUR", interval = SubscriptionInterval.MONTH)
        }
    private var row: DoctorSubscription? = null
    private val subscriptions =
        mock(DoctorSubscriptionRepository::class.java).also { repo ->
            `when`(repo.findTopByDoctor_IdOrderByCreatedAtDesc(4)).thenAnswer { row }
            `when`(repo.findTopByExternalSubscriptionIdOrderByCreatedAtDesc(any() ?: "")).thenAnswer { inv ->
                row?.takeIf { it.externalSubscriptionId == inv.arguments[0] }
            }
            `when`(repo.save(any(DoctorSubscription::class.java) ?: blank())).thenAnswer { inv ->
                (inv.arguments[0] as DoctorSubscription).also { row = it }
            }
        }
    private val service =
        SubscriptionService(
            mock(DoctorProfiles::class.java).also { `when`(it.findById(4)).thenReturn(Optional.of(doctor)) },
            mock(SubscriptionPlanRepository::class.java).also { repo ->
                plans.forEach { (code, plan) -> `when`(repo.findByCode(code)).thenReturn(plan) }
            },
            subscriptions,
            StripeProperties(),
            com.shifa.shared.PublicUrls(AppProperties().apply { publicBaseUrl = "https://api.shifa.uz" }),
            SubscriptionTierService(mock(Users::class.java)),
        )

    private fun blank() = DoctorSubscription(doctor = doctor, plan = plans.getValue("BASIC"), gateway = PaymentGatewayCode.STRIPE)

    private fun checkout(plan: String, subscriptionId: String) =
        service.activateByStripeCheckoutSession(
            Session().apply {
                metadata = mapOf("doctorId" to "4", "planCode" to plan)
                subscription = subscriptionId
            },
        )

    private fun cancel(subscriptionId: String) =
        service.markCancelledByStripeSubscription(Subscription().apply { id = subscriptionId })

    @Test
    fun `a paid checkout raises the tier and its cancellation takes it back`() {
        checkout("PRO", "sub_1")
        assertEquals(SubscriptionTier.PRO, user.subscriptionTier)
        cancel("sub_1")
        assertEquals(SubscriptionTier.BASIC, user.subscriptionTier)
    }

    @Test
    fun `a new plan replacing a running one ends the old plan's tier, so no cancellation leaves it behind`() {
        checkout("PRO", "sub_1")
        checkout("BASIC", "sub_2") // the row now holds BASIC: sub_1's cancellation will never find it
        assertEquals(SubscriptionTier.BASIC, user.subscriptionTier)
        cancel("sub_1")
        cancel("sub_2")
        assertEquals(SubscriptionTier.BASIC, user.subscriptionTier)
    }

    @Test
    fun `a payment event arriving after the cancellation brings nothing back`() {
        checkout("PRO", "sub_1")
        cancel("sub_1")
        service.markPaidByStripeSubscriptionId("sub_1") // Stripe sends events out of order
        assertEquals(SubscriptionTier.BASIC, user.subscriptionTier)
    }

    @Test
    fun `a tier an admin granted outlives the paid plan`() {
        checkout("PRO", "sub_1")
        user.subscriptionTier = SubscriptionTier.PREMIUM // the admin panel
        cancel("sub_1")
        assertEquals(SubscriptionTier.PREMIUM, user.subscriptionTier)
    }
}
