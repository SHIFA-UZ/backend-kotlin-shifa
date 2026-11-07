package com.shifa.appointments.application

import com.shifa.appointments.domain.Appointment
import com.shifa.auth.domain.User
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.security.DoctorPrincipal
import java.time.Instant
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.server.ResponseStatusException

class VideoPresenceServiceTest {
    private val doctor = DoctorProfile(id = 9, user = User(id = 2, passwordHash = "x"), firstName = "A", lastName = "B")
    private val principal = DoctorPrincipal(doctor, listOf(SimpleGrantedAuthority("ROLE_DOCTOR")))
    private val appointment = Appointment(
        id = 10L,
        doctor = doctor,
        patient = PatientProfile(id = 77L, fullName = "Jane Doe"),
        startAt = Instant.parse("2026-11-07T09:00:00Z"),
        endAt = Instant.parse("2026-11-07T09:30:00Z"),
        location = "Video",
    )
    private val appointments = mock(Appointments::class.java).also {
        `when`(it.findById(10L)).thenReturn(Optional.of(appointment))
    }
    private val joined = Instant.parse("2026-11-07T08:58:00Z")

    private fun service(present: List<RoomParticipant>?) = VideoPresenceService(appointments) { room ->
        assertEquals("appointment-10", room)
        present
    }

    @Test
    fun `the patient in the room is found by their token's user id`() {
        val found = service(listOf(RoomParticipant("patient-77", joined))).patientInRoom(principal, 10L)
        assertEquals(joined, found?.joinTime)
    }

    @Test
    fun `an empty room, or only the doctor, is not the patient`() {
        assertNull(service(emptyList()).patientInRoom(principal, 10L))
        assertNull(service(listOf(RoomParticipant("doctor-9", joined))).patientInRoom(principal, 10L))
    }

    @Test
    fun `another doctor is refused`() {
        val other = DoctorProfile(id = 12, user = User(id = 3, passwordHash = "x"), firstName = "C", lastName = "D")
        val e = assertThrows<ResponseStatusException> {
            service(emptyList()).patientInRoom(DoctorPrincipal(other, emptyList()), 10L)
        }
        assertEquals(HttpStatus.FORBIDDEN, e.statusCode)
    }

    @Test
    fun `a patient or staff caller is refused`() {
        val e = assertThrows<ResponseStatusException> { service(emptyList()).patientInRoom("someone", 10L) }
        assertEquals(HttpStatus.FORBIDDEN, e.statusCode)
    }

    @Test
    fun `when the room can't be asked the answer is 503, never a guess`() {
        val e = assertThrows<ResponseStatusException> { service(null).patientInRoom(principal, 10L) }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.statusCode)
    }
}
