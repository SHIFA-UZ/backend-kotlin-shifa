package com.shifa.admin.api.dto

data class SmsRemindersAllowedRequest(val allowed: Boolean)

data class DoctorSubscriptionBillingRequest(
    val trialPeriodMonths: Int? = null,
    val monthlyChargeUsd: Int? = null,
)
