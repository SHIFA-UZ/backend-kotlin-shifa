package com.shifa.appointments.application

import com.shifa.appointments.domain.Appointment
import com.shifa.shared.security.DoctorPrincipal
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** One person in a video room. */
data class RoomParticipant(val userId: String, val joinTime: Instant?)

/** Who is in a video room right now; null when that can't be asked. Daily implements it in infrastructure. */
fun interface VideoRoomPresence {
    fun presence(roomName: String): List<RoomParticipant>?
}

/** The doctor's pre-join question: is my visit's patient already in the room? */
@Service
class VideoPresenceService(
    private val appointments: Appointments,
    private val rooms: VideoRoomPresence,
) {
    /**
     * The patient's place in the visit's room (null: not there), for the visit's own doctor only. 503 when the room
     * can't be asked, so the app shows nothing rather than a wrong "not here yet".
     */
    fun patientInRoom(principal: Any?, appointmentId: Long): RoomParticipant? {
        val appointment = ownAppointment(principal, appointmentId)
        val present = rooms.presence(roomNameFor(appointmentId))
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Video room presence is unavailable")
        // The patient's token names them so (VideoController.generateToken).
        return present.firstOrNull { it.userId == "patient-${appointment.patient.id}" }
    }

    private fun ownAppointment(principal: Any?, appointmentId: Long): Appointment {
        val appointment = appointments.findById(appointmentId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found: $appointmentId") }
        val doctorId = (principal as? DoctorPrincipal)?.profile?.id
        if (doctorId == null || appointment.doctor?.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the visit's doctor can see who is in the room")
        }
        return appointment
    }

    companion object {
        /** The room a visit's video token opens unless the caller names another. */
        fun roomNameFor(appointmentId: Long) = "appointment-$appointmentId"
    }
}
