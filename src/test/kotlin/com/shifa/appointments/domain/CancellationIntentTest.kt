package com.shifa.appointments.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CancellationIntentTest {
    @Test
    fun `cancel next week and cancel it both preview`() {
        assertTrue(
            CancellationIntent.shouldPreview(
                "I would like to cancel one of the appointments from next week",
                emptyList(),
                emptyList(),
            ),
        )
        assertTrue(
            CancellationIntent.shouldPreview(
                "cancel it",
                listOf("I want to cancel the appointment with him for next week"),
                listOf("You have 1 appointment.\n<appointment_list>1211</appointment_list>"),
            ),
        )
        assertFalse(
            CancellationIntent.shouldPreview(
                "list me all the appointments for next week",
                listOf("cancel Franco"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `a misheard surname still yields the first name and next week is kept`() {
        assertEquals("franco harrison", CancellationIntent.patientName("Franco Harrison man"))
        assertEquals("franco herselman", CancellationIntent.patientName("I meant Franco herselman"))
        assertNull(CancellationIntent.patientName("cancel it"))
        assertTrue(
            CancellationIntent.rangeText(
                "Franco Harrison man",
                listOf("I would like to cancel one of the appointments from next week"),
            ).contains("next week"),
        )
    }

    @Test
    fun `sentences and common words are never a patient name`() {
        assertNull(CancellationIntent.patientName("what time is it"))
        assertNull(CancellationIntent.patientName("can you check again"))
        assertNull(CancellationIntent.patientName("I think there should be three"))
        assertNull(CancellationIntent.patientName("hello shifa good morning how are you doing today"))
        assertEquals("franco herselman", CancellationIntent.patientName("Franco Herselman"))
        assertEquals("botir muzrabov", CancellationIntent.patientName("cancel Botir Muzrabov next week"))
    }

    @Test
    fun `a doubt about the count is not a patient name`() {
        val text = "are you sure because I should have three appointments for next week"
        assertNull(CancellationIntent.patientName(text))
        assertTrue(CancellationIntent.isCountQuestion(text))
        assertTrue(
            CancellationIntent.shouldPreview(
                text,
                listOf("I would like to cancel appointment for next week"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `cancel it refers to the visit just shown`() {
        assertTrue(CancellationIntent.refersToShown("cancel it"))
        assertFalse(CancellationIntent.refersToLast("cancel it"))
        assertTrue(CancellationIntent.refersToLast("cancel the last one"))
    }

    @Test
    fun `uzbek bekor is a cancel preview`() {
        assertTrue(
            CancellationIntent.shouldPreview(
                "23-oktabr kunga barcha qabullarni bekor qil.",
                emptyList(),
                emptyList(),
            ),
        )
        assertTrue(
            CancellationIntent.shouldPreview(
                "Menga qabullarni ro'yxatdan olib tashla.",
                emptyList(),
                emptyList(),
            ),
        )
    }
}
