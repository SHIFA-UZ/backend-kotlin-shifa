package com.shifa.appointments.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class RescheduleIntentTest {
    private val today = LocalDate.parse("2026-10-17")
    private val visits = listOf(
        visit(1208, "Botir 2 Muzrabov", "2026-10-21", "12:00"),
        visit(1209, "Botir 2 Muzrabov", "2026-10-22", "15:00"),
        visit(1211, "Franco Herselman", "2026-10-23", "09:00"),
        visit(1210, "Botir 2 Muzrabov", "2026-10-23", "16:00"),
    )
    private val listed = """
        You have 4 appointments scheduled next week.
        <appointment_query>{"start":"2026-10-18T19:00:00Z","endExclusive":"2026-10-25T19:00:00Z","label":"next week"}</appointment_query>
        <appointment_list>1208,1209,1211,1210</appointment_list>
    """.trimIndent()

    @Test
    fun `franco on 23 october is that visit and 5 november is the new day`() {
        val choice = RescheduleIntent.choose(
            visits = visits,
            current = "like to schedule for 5th of November",
            priorUsers = listOf(
                "I would like to reschedule the appointment with Franco herselman on Friday 23rd of October",
                "you just said I have an appointment with Franco herselman on 23rd of October 9:00",
            ),
            priorAssistants = listOf(listed),
            today = today,
            lastListedId = 1210,
        )
        assertEquals(1211, choice!!.visitId)
        assertEquals(LocalDate.parse("2026-11-05"), choice.destination)
        assertNull(choice.time)
    }

    @Test
    fun `the last listed visit moves two weeks from its own day`() {
        val choice = RescheduleIntent.choose(
            visits = visits,
            current = "can we change the last appointment to in 2 weeks",
            priorUsers = emptyList(),
            priorAssistants = listOf(listed),
            today = today,
            lastListedId = 1210,
        )
        assertEquals(1210, choice!!.visitId)
        assertEquals(LocalDate.parse("2026-11-06"), choice.destination)
    }

    @Test
    fun `4 pm on 23 october is botir not franco`() {
        val choice = RescheduleIntent.choose(
            visits = visits,
            current = "the appointment is 23rd of October 4:00 p.m.",
            priorUsers = listOf("can we change the appointment of 23rd of October to in 2 weeks"),
            priorAssistants = listOf(listed),
            today = today,
        )
        assertEquals(1210, choice!!.visitId)
        assertEquals(LocalDate.parse("2026-11-06"), choice.destination)
    }

    @Test
    fun `a move is not a list and not a cancellation yes`() {
        assertTrue(
            RescheduleIntent.shouldMove(
                "I would like to reschedule the appointment with Franco herselman on Friday 23rd of October",
                emptyList(),
                emptyList(),
            ),
        )
        assertFalse(
            RescheduleIntent.shouldMove(
                "list me all the appointments for next week",
                listOf("can we change the last appointment to in 2 weeks"),
                emptyList(),
            ),
        )
        assertFalse(
            RescheduleIntent.shouldMove(
                "yes",
                listOf("cancel Franco tomorrow"),
                listOf("Say yes.\n<appointment_action>abc</appointment_action>"),
            ),
        )
        assertTrue(
            RescheduleIntent.shouldMove(
                "yes",
                listOf("I would like to reschedule Franco"),
                listOf("Would you like me to reschedule it to the 5th of November at 11:00 a.m.?"),
            ),
        )
    }

    @Test
    fun `a time said before the day is kept when the day arrives`() {
        val choice = RescheduleIntent.choose(
            visits = visits,
            current = "move to 5th of November",
            priorUsers = listOf(
                "I would like to reschedule one of the appointments",
                "Franco Hair Salon on 23rd of October 9:00 a.m.",
                "11:00",
            ),
            priorAssistants = listOf(listed),
            today = today,
        )
        assertEquals(1211, choice!!.visitId)
        assertEquals(LocalDate.parse("2026-11-05"), choice.destination)
        assertEquals(LocalTime.of(11, 0), choice.time)
    }

    @Test
    fun `5 october is not rolled into next year`() {
        val choice = RescheduleIntent.choose(
            visits = visits,
            current = "movie to 5th of October",
            priorUsers = listOf(
                "I would like to reschedule one of the appointments",
                "Franco Hair Salon on 23rd of October 9:00 a.m.",
            ),
            priorAssistants = listOf(listed),
            today = today,
        )
        assertEquals(1211, choice!!.visitId)
        assertNull(choice.destination)
        assertEquals(LocalDate.parse("2026-10-05"), choice.pastDay)
    }

    @Test
    fun `uzbek ko chir keeps the 15 00 visit and 24 october 8 30`() {
        assertTrue(
            RescheduleIntent.shouldMove(
                "8:30 to'g'ri keladi.",
                listOf(
                    "Bu hafta uchun qabullarni ko'rsat.",
                    "21 22-oktabr kungi 15:00 dagi qabulni",
                    "Yig'ilishni 22-oktabr 15:00 dagi qabulni 24-oktabrga ko'chir.",
                ),
                emptyList(),
            ),
        )
        val choice = RescheduleIntent.choose(
            visits = visits,
            current = "8:30 to'g'ri keladi.",
            priorUsers = listOf(
                "Bu hafta uchun qabullarni ko'rsat.",
                "21 22-oktabr kungi 15:00 dagi qabulni",
                "Yig'ilishni 22-oktabr 15:00 dagi qabulni 24-oktabrga ko'chir. Qara-chi, 24-oktabrda qaysi vaqtlarda bo'sh vaqtim bor?",
            ),
            priorAssistants = listOf(listed),
            today = today,
        )
        assertEquals(1209, choice!!.visitId)
        assertEquals(LocalDate.parse("2026-10-24"), choice.destination)
        assertEquals(LocalTime.of(8, 30), choice.time)
    }

    @Test
    fun `uzbek 22 oktabr 15 00 names that visit without english months`() {
        val choice = RescheduleIntent.choose(
            visits = visits,
            current = "722 Muzrabov, 22-oktabr, 15:00 dagi qabul.",
            priorUsers = listOf("Yig'ilishni 22-oktabr 15:00 dagi qabulni 24-oktabrga ko'chir."),
            priorAssistants = listOf(listed),
            today = today,
        )
        assertEquals(1209, choice!!.visitId)
        assertEquals(LocalDate.parse("2026-10-24"), choice.destination)
    }

    @Test
    fun `yes after a named destination keeps that day and time`() {
        val choice = RescheduleIntent.choose(
            visits = visits,
            current = "yes",
            priorUsers = listOf(
                "I would like to reschedule the appointment with Franco herselman on Friday 23rd of October",
            ),
            priorAssistants = listOf(
                "You have an appointment with Franco Herselman on Friday, 23rd of October at 9:00 a.m. " +
                    "Would you like me to reschedule it to the 5th of November at 11:00 a.m.?",
            ),
            today = today,
        )
        assertEquals(1211, choice!!.visitId)
        assertEquals(LocalDate.parse("2026-11-05"), choice.destination)
        assertEquals(LocalTime.of(11, 0), choice.time)
    }

    private fun visit(id: Long, name: String, day: String, time: String) = RescheduleIntent.Visit(
        id = id,
        patientName = name,
        day = LocalDate.parse(day),
        time = LocalTime.parse(time),
    )
}
