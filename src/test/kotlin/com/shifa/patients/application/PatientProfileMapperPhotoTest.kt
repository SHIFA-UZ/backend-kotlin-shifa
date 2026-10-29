package com.shifa.patients.application

import com.shifa.shared.config.AppProperties
import com.shifa.shared.security.FileUrlSigner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PatientProfileMapperPhotoTest {
    private val props = AppProperties().apply { publicBaseUrl = "https://api.shifa.uz"; storageRoot = "/tmp" }
    private val signer = FileUrlSigner("a_really_long_random_64+_char_secret_development_only_xxxxxxxx", Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC))
    private val mapper = PatientProfileMapper(signer, com.shifa.shared.PublicUrls(props))

    @Test
    fun `own photo is absolute and signed`() {
        val url = mapper.normalizePhotoUrl("patients/anna-karimova-7.jpg", ownerProfileId = 7L)!!
        assertTrue(url.startsWith("https://api.shifa.uz/patients/anna-karimova-7.jpg?exp="))
        assertTrue(url.contains("&sig="))
    }

    @Test
    fun `another patient's photo or a document path is never signed`() {
        assertEquals("https://api.shifa.uz/patients/anna-karimova-7.jpg", mapper.normalizePhotoUrl("patients/anna-karimova-7.jpg", ownerProfileId = 8L))
        assertEquals("https://api.shifa.uz/patientdocuments/7/report-7.pdf", mapper.normalizePhotoUrl("patientdocuments/7/report-7.pdf", ownerProfileId = 7L))
        assertEquals("https://api.shifa.uz/patients/anna-karimova-7.jpg", mapper.normalizePhotoUrl("patients/anna-karimova-7.jpg"))
    }

    @Test
    fun `doctor avatars and external urls pass through unchanged`() {
        assertEquals("https://api.shifa.uz/doctors/dr-1.jpg", mapper.normalizePhotoUrl("doctors/dr-1.jpg", ownerProfileId = 1L))
        assertEquals("https://cdn.example.com/p.png", mapper.normalizePhotoUrl("https://cdn.example.com/p.png", ownerProfileId = 1L))
    }
}

class PatientProfileMapperPhotoEdgeTest {
    private val props = AppProperties().apply { publicBaseUrl = "https://api.shifa.uz"; storageRoot = "/tmp" }
    private val signer = FileUrlSigner("a_really_long_random_64+_char_secret_development_only_xxxxxxxx", Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC))
    private val mapper = PatientProfileMapper(signer, com.shifa.shared.PublicUrls(props))

    @Test
    fun `empty slug from a non-latin name and encoded names are still the owner's photo`() {
        assertTrue(mapper.normalizePhotoUrl("patients/-7.jpg", ownerProfileId = 7L)!!.contains("sig="))
        assertTrue(mapper.normalizePhotoUrl("patients/anna%20k-7.jpeg", ownerProfileId = 7L)!!.contains("sig="))
    }

    @Test
    fun `an id that is only a prefix of another does not match`() {
        assertEquals("https://api.shifa.uz/patients/anna-17.jpg", mapper.normalizePhotoUrl("patients/anna-17.jpg", ownerProfileId = 7L))
        assertEquals("https://api.shifa.uz/patients/x/anna-7.jpg", mapper.normalizePhotoUrl("patients/x/anna-7.jpg", ownerProfileId = 7L))
    }
}
