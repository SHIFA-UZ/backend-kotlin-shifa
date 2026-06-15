package com.shifa.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.AppProperties
import com.shifa.domain.DoctorBilling
import com.shifa.domain.DoctorProfile
import com.shifa.domain.DoctorSettings
import com.shifa.domain.DoctorStartTab
import com.shifa.domain.Notification
import com.shifa.repo.DoctorBillingRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorSettingsRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.UserRepository
import com.shifa.security.DoctorPrincipal
import com.shifa.service.FcmService
import com.shifa.service.AdminClinicService
import com.shifa.service.ScheduleValidityService
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.Locale

@RestController
@RequestMapping("/api/doctors/me")
class DoctorController(
    private val users: UserRepository,
    private val doctors: DoctorProfileRepository,
    private val billingRepo: DoctorBillingRepository,
    private val settingsRepo: DoctorSettingsRepository,
    private val appProps: AppProperties,
    private val scheduleValidityService: ScheduleValidityService,
    private val notifications: NotificationRepository,
    private val fcmService: FcmService,
    private val subscriptionTierService: com.shifa.service.SubscriptionTierService,
    private val adminClinicService: AdminClinicService,
) {

    // -------------------- helpers --------------------

    private fun currentDoctor(principal: DoctorPrincipal): DoctorProfile =
        principal.profile

    /** Build absolute URL from avatarUrl using app.publicBaseUrl */
    private fun normalizeAvatar(avatarUrl: String?): String? {
        val trimmed = avatarUrl?.trim() ?: return null
        val isAbs = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return if (isAbs) trimmed
        else "${appProps.publicBaseUrl.removeSuffix("/")}/${trimmed.removePrefix("/")}"
    }

    /** Effective schedule validity for API: profile columns or min/max from periods (after migration). */
    private fun effectiveScheduleFrom(d: DoctorProfile): String? =
        d.scheduleValidFrom?.toString() ?: scheduleValidityService.getPeriods(d).minOfOrNull { it.validFrom }?.toString()

    private fun effectiveScheduleUntil(d: DoctorProfile): String? =
        d.scheduleValidUntil?.toString() ?: scheduleValidityService.getPeriods(d).maxOfOrNull { it.validUntil }?.toString()

    /** Slugify name for file naming */
    private fun slugify(name: String?): String {
        val base = (name ?: "doctor").lowercase(Locale.getDefault()).trim()
        val norm = Normalizer.normalize(base, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return norm.replace("[^a-z0-9]+".toRegex(), "-")
            .replace("[-]{2,}".toRegex(), "-")
            .trim('-')
    }

    // -------------------- DTOs --------------------

    data class ProfileDto(
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

    data class FcmTokenRequest(val fcmToken: String?)

    data class UploadResp(val photoUrl: String)

    // -------------------- Test push: doctor self-check --------------------

    /**
     * Convenience endpoint to verify that FCM from backend → doctor app works
     * for the currently authenticated doctor.
     *
     * Usage:
     *   - Call POST /api/doctors/me/test-notification (e.g. from Postman)
     *   - If the doctor app receives a push titled "Test Doctor Notification",
     *     FCM wiring is correct end-to-end.
     */
    @PostMapping("/test-notification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun sendTestNotification(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ) {
        val d = currentDoctor(principal)

        val notif = Notification(
            doctor = d,
            title = "Test Doctor Notification",
            message = "If you see this on your device, doctor FCM is working correctly.",
            type = Notification.Type.GENERAL
        )
        val saved = notifications.save(notif)
        d.fcmToken?.let { token ->
            fcmService.sendDoctorNotification(token, saved)
        }
    }

    // -------------------- GET all sections --------------------
    
    @GetMapping
    @Transactional(readOnly = true)
    fun getAll(
        @AuthenticationPrincipal principal: DoctorPrincipal
    ): Map<String, Any?> {

        val d = currentDoctor(principal)
        val b = billingRepo.findByDoctorId(d.id).orElse(null)
        val s = settingsRepo.findByDoctorId(d.id).orElse(null)

        return mapOf(
            "profile" to ProfileDto(
                firstName = d.firstName,
                lastName = d.lastName,
                dob = d.dob?.toString(),
                gender = d.gender,
                address = d.address,
                clinic = d.clinic,
                profession = d.profession,
                avatarUrl = d.avatarUrl,
                photoUrl = normalizeAvatar(d.avatarUrl),
                scheduleValidFrom = effectiveScheduleFrom(d),
                scheduleValidUntil = effectiveScheduleUntil(d),
                biography = d.biography,
                services = d.services,
                certificates = d.certificates,
                telegram = d.telegram,
                instagram = d.instagram,
                latitude = d.latitude,
                longitude = d.longitude,
                locationCountry = d.locationCountry,
                locationRegion = d.locationRegion,
                locationDistrict = d.locationDistrict,
                locationCity = d.locationCity,
                locationPostalCode = d.locationPostalCode,
                locationStreetAddress = d.locationStreetAddress,
                timeZone = d.timeZone,
                consultationPriceMinor = d.consultationPriceMinor,
                consultationCurrency = d.consultationCurrency,
                smsRemindersAllowed = d.smsRemindersAllowed,
            ),
            "contact" to ContactDto(
                phone = d.user.phone ?: "",
                email = d.user.email
            ),
            "billing" to BillingDto(
                billingName = b?.billingName,
                billingEmail = b?.billingEmail,
                iban = b?.iban,
                taxId = b?.taxId,
                stripeConnectAccountId = b?.stripeConnectAccountId,
                clickMerchantId = b?.clickMerchantId,
                paymeMerchantId = b?.paymeMerchantId
            ),
            "settings" to SettingsDto(
                country = s?.country,
                language = s?.language,
                twoFA = s?.twoFactor ?: false,
                encryptedDocs = s?.encryptedDocs ?: true,
                defaultStartTab = DoctorStartTab.normalize(s?.defaultStartTab),
            ),
            "subscription" to mapOf(
                "tier" to subscriptionTierService.tierOf(d.user).name,
                "features" to subscriptionTierService.availableFeatures(d.user).map { it.name }
            )
        )
    }

    // -------------------- FCM Token (push notifications) --------------------

    @PutMapping("/fcm-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateFcmToken(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody req: FcmTokenRequest
    ) {
        val d = currentDoctor(principal)
        d.fcmToken = req.fcmToken?.takeIf { it.isNotBlank() }
        doctors.save(d)
    }

    // -------------------- PATCH sections --------------------

    @PatchMapping("/profile")
    @Transactional
    fun patchProfile(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: ProfileDto
    ): ProfileDto {

        val d = currentDoctor(principal)

        body.firstName?.let { d.firstName = it }
        body.lastName?.let { d.lastName = it }
        body.dob?.let { d.dob = java.time.LocalDate.parse(it) }
        body.gender?.let { d.gender = it }
        body.address?.let { d.address = it }
        if (body.clinicId == null) {
            body.clinic?.let { d.clinic = it }
        }
        body.profession?.let { d.profession = it }
        body.avatarUrl?.let { d.avatarUrl = it }
        body.scheduleValidFrom?.let { d.scheduleValidFrom = java.time.LocalDate.parse(it) }
        body.scheduleValidUntil?.let { d.scheduleValidUntil = java.time.LocalDate.parse(it) }
        body.biography?.let { d.biography = it }
        body.services?.let { d.services = it }
        body.certificates?.let { d.certificates = it }
        body.telegram?.let { d.telegram = it }
        body.instagram?.let { d.instagram = it }
        body.latitude?.let { d.latitude = it }
        body.longitude?.let { d.longitude = it }
        body.locationCountry?.let { d.locationCountry = it }
        body.locationRegion?.let { d.locationRegion = it }
        body.locationDistrict?.let { d.locationDistrict = it }
        body.locationCity?.let { d.locationCity = it }
        body.locationPostalCode?.let { d.locationPostalCode = it }
        body.locationStreetAddress?.let { d.locationStreetAddress = it }
        body.timeZone?.let { d.timeZone = it }
        body.consultationPriceMinor?.let { d.consultationPriceMinor = it }
        body.consultationCurrency?.let { d.consultationCurrency = it.uppercase() }

        doctors.save(d)
        body.clinicId?.let { adminClinicService.assignDoctor(it, d.id!!) }
        val updated = doctors.findById(d.id!!).orElse(d)

        return ProfileDto(
            firstName = updated.firstName,
            lastName = updated.lastName,
            dob = updated.dob?.toString(),
            gender = updated.gender,
            address = updated.address,
            clinic = updated.clinic,
            profession = updated.profession,
            avatarUrl = updated.avatarUrl,
            photoUrl = normalizeAvatar(updated.avatarUrl),
            scheduleValidFrom = effectiveScheduleFrom(updated),
            scheduleValidUntil = effectiveScheduleUntil(updated),
            biography = updated.biography,
            services = updated.services,
            certificates = updated.certificates,
            telegram = updated.telegram,
            instagram = updated.instagram,
            latitude = updated.latitude,
            longitude = updated.longitude,
            locationCountry = updated.locationCountry,
            locationRegion = updated.locationRegion,
            locationDistrict = updated.locationDistrict,
            locationCity = updated.locationCity,
            locationPostalCode = updated.locationPostalCode,
            locationStreetAddress = updated.locationStreetAddress,
            timeZone = updated.timeZone,
            consultationPriceMinor = updated.consultationPriceMinor,
            consultationCurrency = updated.consultationCurrency
        )
    }

    @PatchMapping("/contact")
    fun patchContact(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: ContactDto
    ): ContactDto {
        val d = currentDoctor(principal)
        val u = d.user
        u.phone = body.phone.trim()
        u.email = body.email?.trim()?.takeIf { it.isNotEmpty() }
        try {
            users.save(u)
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Phone or email is already in use by another account"
            )
        }
        return ContactDto(phone = u.phone ?: "", email = u.email)
    }

    @PatchMapping("/billing")
    fun patchBilling(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: BillingDto
    ): BillingDto {

        val d = currentDoctor(principal)
        val b = billingRepo.findByDoctorId(d.id).orElse(DoctorBilling(doctor = d))

        b.billingName = body.billingName
        b.billingEmail = body.billingEmail
        b.iban = body.iban
        b.taxId = body.taxId
        b.stripeConnectAccountId = body.stripeConnectAccountId
        b.clickMerchantId = body.clickMerchantId
        b.paymeMerchantId = body.paymeMerchantId
        b.updatedAt = OffsetDateTime.now()

        billingRepo.save(b)
        return body
    }

    @PatchMapping("/settings")
    fun patchSettings(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: SettingsDto
    ): SettingsDto {

        val d = currentDoctor(principal)
        val s = settingsRepo.findByDoctorId(d.id).orElse(DoctorSettings(doctor = d))

        s.country = body.country
        s.language = body.language
        s.twoFactor = body.twoFA
        s.encryptedDocs = body.encryptedDocs
        s.defaultStartTab = DoctorStartTab.normalize(body.defaultStartTab)
        s.updatedAt = OffsetDateTime.now()

        settingsRepo.save(s)
        return body
    }

    // -------------------- POST /photo --------------------

    @PostMapping("/photo")
    fun uploadPhoto(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam("file") file: MultipartFile
    ): UploadResp {

        val d = currentDoctor(principal)

        val fullName = "${d.firstName} ${d.lastName}".trim()
        val slug = slugify(fullName)

        val original = file.originalFilename ?: "photo.jpg"
        val ext = original.substringAfterLast('.', "jpg").lowercase(Locale.getDefault())

        val imagesRoot = Path.of(appProps.storageRoot)
        val doctorsDir = imagesRoot.resolve("doctors")
        Files.createDirectories(doctorsDir)

        val target = doctorsDir.resolve("$slug-${d.id}.$ext")
        file.inputStream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }

        d.avatarUrl = "doctors/$slug-${d.id}.$ext"
        doctors.save(d)

        return UploadResp(photoUrl = normalizeAvatar(d.avatarUrl)!!)
    }

    // -------------------- POST /certificate --------------------

    @PostMapping("/certificate")
    fun uploadCertificate(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam("file") file: MultipartFile
    ): UploadResp {

        val d = currentDoctor(principal)

        val fullName = "${d.firstName} ${d.lastName}".trim()
        val slug = slugify(fullName)

        val original = file.originalFilename ?: "certificate.pdf"
        val ext = original.substringAfterLast('.', "pdf").lowercase(Locale.getDefault())

        val imagesRoot = Path.of(appProps.storageRoot)
        val certificatesDir = imagesRoot.resolve("certificates")
        Files.createDirectories(certificatesDir)

        val timestamp = System.currentTimeMillis()
        val target = certificatesDir.resolve("$slug-${d.id}-$timestamp.$ext")
        file.inputStream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }

        val certUrl = "certificates/$slug-${d.id}-$timestamp.$ext"
        val normalizedUrl = normalizeAvatar(certUrl)!!

        // Add to existing certificates JSON array
        val objectMapper = ObjectMapper()
        val existingCerts = try {
            if (d.certificates != null && d.certificates!!.isNotBlank()) {
                objectMapper.readValue(
                    d.certificates,
                    Array<String>::class.java
                ).toList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        val updatedCerts = (existingCerts + normalizedUrl).distinct()
        d.certificates = objectMapper.writeValueAsString(updatedCerts)
        doctors.save(d)

        return UploadResp(photoUrl = normalizedUrl)
    }
}
