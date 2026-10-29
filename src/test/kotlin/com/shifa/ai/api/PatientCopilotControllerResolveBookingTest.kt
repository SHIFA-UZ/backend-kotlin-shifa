package com.shifa.ai.api


import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.shifa.ai.domain.OutputLanguage
import com.shifa.shared.config.AppProperties
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.domain.PatientProfile
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.doctors.infrastructure.DoctorProfileRepository
import com.shifa.doctors.infrastructure.DoctorReviewRepository
import com.shifa.patients.infrastructure.PatientProfileRepository
import com.shifa.shared.security.PatientPrincipal
import com.shifa.ai.infrastructure.OpenAiResponsesService
import com.shifa.ai.application.PatientCopilotBookingService
import com.shifa.ai.application.PatientCopilotContextService
import com.shifa.appointments.application.PatientDaySlotsService
import com.shifa.payments.application.SubscriptionTierService
import com.shifa.ai.application.TranscriptionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Optional

class PatientCopilotControllerResolveBookingTest {

    private fun buildController(
        aiService: OpenAiResponsesService,
        patientProfiles: PatientProfileRepository,
        doctorProfiles: DoctorProfileRepository,
        reviewRepo: DoctorReviewRepository,
        booking: PatientCopilotBookingService,
        daySlots: PatientDaySlotsService
    ): PatientCopilotController {
        return PatientCopilotController(
            aiService = aiService,
            transcriptionService = mock(TranscriptionService::class.java),
            patientProfiles = patientProfiles,
            doctorProfiles = doctorProfiles,
            reviewRepository = reviewRepo,
            copilotBookingService = booking,
            copilotContextService = mock(PatientCopilotContextService::class.java),
            daySlotsService = daySlots,
            appProps = AppProperties(),
            objectMapper = jacksonObjectMapper(),
            subscriptionTierService = mock(SubscriptionTierService::class.java),
            uploadGuard = com.shifa.shared.UploadGuard(AppProperties()),
            sseExecutor = java.util.concurrent.Executor { it.run() },
        )
    }

    private fun principal(user: User): PatientPrincipal =
        PatientPrincipal(user, listOf(SimpleGrantedAuthority("ROLE_PATIENT")))

    @Test
    fun `resolve booking uses deterministic fallback for doctor time and visit type`() {
        val ai = mock(OpenAiResponsesService::class.java)
        val patients = mock(PatientProfileRepository::class.java)
        val doctors = mock(DoctorProfileRepository::class.java)
        val reviews = mock(DoctorReviewRepository::class.java)
        val booking = mock(PatientCopilotBookingService::class.java)
        val daySlots = mock(PatientDaySlotsService::class.java)

        val user = User(id = 11L, passwordHash = "x", role = Role.PATIENT, enabled = true)
        val patient = PatientProfile(id = 77L, fullName = "Pat One", user = user, timeZone = "UTC")
        val doctorUser = User(id = 44L, passwordHash = "x", role = Role.DOCTOR, enabled = true)
        val doctor = DoctorProfile(
            id = 55L,
            user = doctorUser,
            firstName = "Greg",
            lastName = "House",
            profession = "cardiologist"
        )

        `when`(patients.findByUserId(11L)).thenReturn(Optional.of(patient))
        `when`(doctors.findById(55L)).thenReturn(Optional.of(doctor))
        `when`(
            ai.resolvePatientBookingIntent(
                listOf(AiMessageDto("user", "Please book doctor 55 tomorrow at 14:30 video")),
                OutputLanguage.EN,
                "UTC",
                listOf(55L)
            )
        )
            .thenReturn(
                PatientBookingIntentResolution(
                    bookNow = false,
                    doctorId = null,
                    preferredStartAtUtc = null,
                    isVideo = null,
                    userExplicitConsentToAutoBook = true
                )
            )
        val zone = ZoneId.of("UTC")
        val tomorrow = ZonedDateTime.now(zone).toLocalDate().plusDays(1)
        val parsedPreferred = tomorrow.atTime(14, 30).atZone(zone).toInstant()
        `when`(daySlots.nextAvailableStartAt(doctor, parsedPreferred.minusSeconds(3 * 3600), 28))
            .thenReturn(parsedPreferred.plusSeconds(1800))

        val controller = buildController(ai, patients, doctors, reviews, booking, daySlots)
        val body = PatientCopilotResolveBookingRequest(
            messages = listOf(
                AiMessageDto("user", "Please book doctor 55 tomorrow at 14:30 video")
            ),
            language = OutputLanguage.EN,
            allowedDoctorIds = listOf(55L),
            confirmAutoBook = false
        )

        val res = controller.resolveBookingFromChat(principal(user), body)
        assertEquals(false, res["booked"])
        assertEquals(true, res["needsClientConfirmation"])
        assertTrue((res["previewMessage"] as String).contains("Greg House"))
    }

    @Test
    fun `resolve booking returns NO_UPCOMING_SLOTS when doctor has no free slot`() {
        val ai = mock(OpenAiResponsesService::class.java)
        val patients = mock(PatientProfileRepository::class.java)
        val doctors = mock(DoctorProfileRepository::class.java)
        val reviews = mock(DoctorReviewRepository::class.java)
        val booking = mock(PatientCopilotBookingService::class.java)
        val daySlots = mock(PatientDaySlotsService::class.java)

        val user = User(id = 12L, passwordHash = "x", role = Role.PATIENT, enabled = true)
        val patient = PatientProfile(id = 78L, fullName = "Pat Two", user = user, timeZone = "UTC")
        val doctorUser = User(id = 45L, passwordHash = "x", role = Role.DOCTOR, enabled = true)
        val doctor = DoctorProfile(
            id = 66L,
            user = doctorUser,
            firstName = "Jane",
            lastName = "Doe",
            profession = "dermatologist"
        )

        `when`(patients.findByUserId(12L)).thenReturn(Optional.of(patient))
        `when`(doctors.findById(66L)).thenReturn(Optional.of(doctor))
        `when`(
            ai.resolvePatientBookingIntent(
                listOf(AiMessageDto("user", "Book this doctor tomorrow 11:00 in person")),
                OutputLanguage.EN,
                "UTC",
                listOf(66L)
            )
        )
            .thenReturn(
                PatientBookingIntentResolution(
                    bookNow = true,
                    doctorId = 66L,
                    preferredStartAtUtc = "2030-01-01T10:00:00Z",
                    isVideo = false,
                    userExplicitConsentToAutoBook = true
                )
            )
        `when`(daySlots.nextAvailableStartAt(doctor, Instant.parse("2030-01-01T07:00:00Z"), 28))
            .thenReturn(null)

        val controller = buildController(ai, patients, doctors, reviews, booking, daySlots)
        val body = PatientCopilotResolveBookingRequest(
            messages = listOf(AiMessageDto("user", "Book this doctor tomorrow 11:00 in person")),
            language = OutputLanguage.EN,
            allowedDoctorIds = listOf(66L),
            confirmAutoBook = false
        )

        val res = controller.resolveBookingFromChat(principal(user), body)
        assertEquals(false, res["booked"])
        assertEquals("NO_UPCOMING_SLOTS", res["reasonCode"])
    }
}

