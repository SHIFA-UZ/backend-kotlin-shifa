package com.shifa.payments.infrastructure

import com.shifa.payments.domain.Payment
import com.shifa.payments.domain.PaymentGatewayCode
import com.shifa.payments.domain.PaymentKind
import com.shifa.payments.domain.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PaymentRepository : JpaRepository<Payment, Long> {
    fun findByExternalRef(externalRef: String): Payment?
    /** Newest first. Several open rows can exist for one appointment on data created before V119. */
    fun findByAppointment_IdAndStatusInOrderByCreatedAtDesc(appointmentId: Long, statuses: Collection<PaymentStatus>): List<Payment>
    fun findFirstByAppointment_IdAndStatusOrderByCreatedAtDesc(appointmentId: Long, status: PaymentStatus): Payment?
    fun findByGatewayPaymentId(gatewayPaymentId: String): Payment?
    fun findByKindAndStatus(kind: PaymentKind, status: PaymentStatus): List<Payment>

    @Query(
        """
        SELECT p FROM Payment p
        WHERE p.kind = :kind
          AND p.gateway = :gateway
          AND p.status = :status
          AND p.treatmentPlan.id = :planId
          AND p.amountMinor = :amountMinor
          AND (
            (:installmentItemId IS NULL AND p.installmentItem IS NULL)
            OR p.installmentItem.id = :installmentItemId
          )
        ORDER BY p.createdAt DESC
        """
    )
    fun findPendingTreatmentPlanCheckout(
        @Param("kind") kind: PaymentKind,
        @Param("gateway") gateway: PaymentGatewayCode,
        @Param("status") status: PaymentStatus,
        @Param("planId") planId: Long,
        @Param("amountMinor") amountMinor: Long,
        @Param("installmentItemId") installmentItemId: Long?,
    ): List<Payment>
}
