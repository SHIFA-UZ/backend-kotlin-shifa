package com.shifa.appointments

import com.shifa.ApplicationContextTest
import com.shifa.appointments.api.CalendarController
import com.shifa.appointments.api.ScheduleController
import com.shifa.appointments.api.dto.DateSpecificRuleDto
import com.shifa.shared.security.DoctorPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The user's report (2026-11-11): extra hours added for a date did not show on the calendar. They did inside the
 * schedule's validity period; on a date past it the whole day was dropped, extra hours included, and a working-day
 * expansion was refused against weekly hours that don't apply there. Against PostgreSQL with the dev seed
 * (seed.doctor1: Mon-Fri 09:00-17:00, valid 30 days back to a year ahead). Rolled back.
 */
@SpringBootTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "logging.file.name=build/shifa-backend-test.log",
        "app.dev-seed=true",
    ],
)
class ExtraHoursOutsideValidityIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: PlatformTransactionManager
    @Autowired lateinit var principals: com.shifa.shared.security.PrincipalResolverService
    @Autowired lateinit var schedule: ScheduleController
    @Autowired lateinit var calendar: CalendarController

    companion object {
        @JvmStatic
        @BeforeAll
        fun requirePostgres() = ApplicationContextTest.requirePostgres()
    }

    private fun <T> rolledBack(block: () -> T): T =
        TransactionTemplate(tx).execute { status -> block().also { status.setRollbackOnly() } }!!

    private fun doctor1(): DoctorPrincipal {
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'seed.doctor1@shifa.dev'", Long::class.java)!!
        return principals.getPrincipalForToken(userId, "DOCTOR") as DoctorPrincipal
    }

    /** A Friday after the seed's validity period ends (it runs a year and a month ahead of today at most). */
    private fun fridayPastValidity(doctor: DoctorPrincipal): java.time.LocalDate {
        val until = jdbc.queryForObject(
            "SELECT MAX(valid_until) FROM schedule_validity_periods WHERE doctor_id = ?",
            java.time.LocalDate::class.java,
            doctor.profile.id,
        )!!
        return generateSequence(until.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek == java.time.DayOfWeek.FRIDAY }
    }

    @Test
    fun `extra hours past the validity period show on the calendar, the weekly hours there do not`() = rolledBack<Unit> {
        val doctor = doctor1()
        val friday = fridayPastValidity(doctor)
        assertThat(calendar.byDay(doctor, friday.toString(), null, null).filter { it.type == "FREE_SLOT" }).isEmpty()

        // 09:00-11:00 on a weekday: weekly hours don't apply past the period, so nothing is overridden.
        schedule.createDateSpecificRule(
            doctor,
            DateSpecificRuleDto(startDate = "$friday", endDate = "$friday", startTime = "09:00", endTime = "11:00", slotMinutes = 30),
        )

        val free = calendar.byDay(doctor, friday.toString(), null, null).filter { it.type == "FREE_SLOT" }
        assertThat(free).hasSize(4) // 09:00, 09:30, 10:00, 10:30 in the practice zone; not the weekly 09:00-17:00
    }
}
