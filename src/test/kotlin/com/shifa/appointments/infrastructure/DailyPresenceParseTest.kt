package com.shifa.appointments.infrastructure

import com.shifa.appointments.application.RoomParticipant
import com.shifa.shared.config.DailyProperties
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/** Daily's `GET /rooms/{name}/presence` answer, as its reference documents it. */
class DailyPresenceParseTest {
    private val daily = DailyVideoService(DailyProperties(), ObjectMapper())

    @Test
    fun `participants come with their user id and join time`() {
        val body = """{"total_count":2,"data":[
            {"id":"a","room":"appointment-10","userId":"patient-77","userName":"Jane","mtgSessionId":"s","joinTime":"2026-11-07T08:58:00.000Z","duration":30},
            {"id":"b","room":"appointment-10","userId":"doctor-9","userName":"A B","joinTime":"bad","duration":5}
        ]}"""
        assertEquals(
            listOf(
                RoomParticipant("patient-77", Instant.parse("2026-11-07T08:58:00Z")),
                RoomParticipant("doctor-9", null),
            ),
            daily.parsePresence(body),
        )
    }

    @Test
    fun `an empty room is an empty list, an answer without data is unknown`() {
        assertEquals(emptyList<RoomParticipant>(), daily.parsePresence("""{"total_count":0,"data":[]}"""))
        assertNull(daily.parsePresence("""{"error":"x"}"""))
    }

    @Test
    fun `without an api key daily is not asked`() {
        assertNull(daily.presence("appointment-10"))
    }
}
