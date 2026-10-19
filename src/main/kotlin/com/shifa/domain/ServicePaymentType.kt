package com.shifa.domain

/** How a priced service is collected. Free visits use [DoctorService.isFreeConsultation], not a third value. */
enum class ServicePaymentType {
    PAY_NOW,
    PAY_LATER,
}
