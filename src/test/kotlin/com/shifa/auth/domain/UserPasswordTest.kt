package com.shifa.auth.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** One password per app on an account that is a patient and also a doctor or staff (V133); one otherwise. */
class UserPasswordTest {
    private fun user() = User(email = "a@shifa.uz", passwordHash = "shared", role = Role.DOCTOR)

    @Test
    fun `the first change on either side splits the passwords, later changes keep to their side`() {
        val patientFirst = user().apply { setPassword("patient-1", patientApp = true, dual = true) }
        assertThat(patientFirst.passwordHashFor(patientApp = true)).isEqualTo("patient-1")
        assertThat(patientFirst.passwordHashFor(patientApp = false)).isEqualTo("shared")

        val doctorFirst = user().apply { setPassword("doctor-1", patientApp = false, dual = true) }
        assertThat(doctorFirst.passwordHashFor(patientApp = true)).isEqualTo("shared") // the patient keeps the old one
        assertThat(doctorFirst.passwordHashFor(patientApp = false)).isEqualTo("doctor-1")
        doctorFirst.setPassword("doctor-2", patientApp = false, dual = true)
        assertThat(doctorFirst.passwordHashFor(patientApp = true)).isEqualTo("shared")
    }

    @Test
    fun `an account with one role has one password for every app`() {
        val patient = user().apply { setPassword("new", patientApp = true, dual = false) }
        assertThat(patient.patientPasswordHash).isNull()
        assertThat(patient.passwordHashFor(patientApp = true)).isEqualTo("new")
        assertThat(patient.passwordHashFor(patientApp = false)).isEqualTo("new")
    }
}
