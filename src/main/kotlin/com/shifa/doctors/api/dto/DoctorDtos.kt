package com.shifa.doctors.api.dto

import com.shifa.doctors.domain.DoctorStartTab
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class ProfileDto(
    val id: Long? = null,
    val firstName: String?,
    val lastName: String?,
    val dob: String?,
    val gender: String?,
    val address: String?,
    val clinic: String?,
    val clinicId: Long? = null,
    val profession: String?,
    val avatarUrl: String?,
    val photoUrl: String?,
    val scheduleValidFrom: String?,
    val scheduleValidUntil: String?,
    val biography: String?,
    val services: String?, // JSON array string
    val certificates: String?, // JSON array string
    val telegram: String?,
    val instagram: String?,
    val latitude: Double?,
    val longitude: Double?,
    // Structured location fields
    val locationCountry: String?,
    val locationRegion: String?,
    val locationDistrict: String?,
    val locationCity: String?,
    val locationPostalCode: String?,
    val locationStreetAddress: String?,
    /** IANA time zone id (e.g. Europe/Berlin). Used for scheduling and calendar. */
    val timeZone: String?,
    val consultationPriceMinor: Long?,
    val consultationCurrency: String?,
    val smsRemindersAllowed: Boolean = false,
)

data class ContactDto(
    @field:NotBlank val phone: String,
    @field:Email val email: String?
)

data class BillingDto(
    val billingName: String?,
    val billingEmail: String?,
    val iban: String?,
    val taxId: String?,
    val stripeConnectAccountId: String?,
    val clickMerchantId: String?,
    val paymeMerchantId: String?
)

data class SettingsDto(
    val country: String?,
    val language: String?,
    val twoFA: Boolean,
    val encryptedDocs: Boolean,
    val defaultStartTab: String = DoctorStartTab.HOME,
)

data class LanguageRequest(val language: String?)
