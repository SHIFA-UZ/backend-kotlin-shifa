package com.shifa.auth

import com.shifa.ApplicationContextTest
import com.shifa.auth.application.PatientAccountDeletionService
import com.shifa.auth.application.UserRoles
import com.shifa.auth.application.Users
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserRole
import com.shifa.doctors.application.DoctorProfiles
import com.shifa.patients.application.PatientProfiles
import com.shifa.patients.domain.PatientProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The user's report (2026-11-11): a doctor with a patient account under the same e-mail (one account, two roles)
 * deleted the patient account and lost the doctor account too. Against PostgreSQL with the dev seed: deleting one
 * side keeps the account and its other role, and a deleted doctor side drops out of what patients see.
 * Every test runs in a rolled-back transaction.
 */
@SpringBootTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "logging.file.name=build/shifa-backend-test.log",
        "app.dev-seed=true",
    ],
)
class AccountSideDeletionIntegrationTest {
    @Autowired lateinit var tx: PlatformTransactionManager
    @Autowired lateinit var users: Users
    @Autowired lateinit var userRoles: UserRoles
    @Autowired lateinit var patients: PatientProfiles
    @Autowired lateinit var doctors: DoctorProfiles
    @Autowired lateinit var patientDeletion: PatientAccountDeletionService

    companion object {
        @JvmStatic
        @BeforeAll
        fun requirePostgres() = ApplicationContextTest.requirePostgres()
    }

    private fun <T> rolledBack(block: () -> T): T =
        TransactionTemplate(tx).execute { status -> block().also { status.setRollbackOnly() } }!!

    /** seed.doctor1 linked to the patient app, as create-patient-for-doctor leaves it. */
    private fun linkedDoctor(): User {
        val doctor = users.findByEmailIgnoreCase("seed.doctor1@shifa.dev").orElseThrow()
        userRoles.save(UserRole(user = doctor, role = Role.PATIENT))
        patients.save(PatientProfile(fullName = "Ulugbek Karimov", email = doctor.email).also { it.user = doctor })
        return doctor
    }

    @Test
    fun `deleting the patient side keeps the doctor account`() = rolledBack<Unit> {
        val doctor = linkedDoctor()
        val profileId = patients.findByUserId(doctor.id).orElseThrow().id!!

        patientDeletion.deletePatientAccount(doctor.id)

        val after = users.findById(doctor.id).orElseThrow()
        assertThat(after.enabled).isTrue()
        assertThat(after.accountStatus).isEqualTo(User.AccountStatus.ACTIVE)
        assertThat(after.email).isEqualTo("seed.doctor1@shifa.dev")
        assertThat(after.role).isEqualTo(Role.DOCTOR)
        assertThat(userRoles.rolesOf(after)).containsExactly(Role.DOCTOR)
        // The records stay, anonymised and no longer this account's; a new patient account gets a new profile.
        val profile = patients.findById(profileId).orElseThrow()
        assertThat(profile.fullName).isEqualTo("Deleted User")
        assertThat(profile.user).isNull()
        assertThat(patients.findByUserId(doctor.id)).isEmpty
        assertThat(doctors.isListed(doctors.findByUserId(doctor.id).orElseThrow())).isTrue()
    }

    @Test
    fun `a deleted doctor side drops out of what patients see, other doctors stay`() = rolledBack<Unit> {
        val doctor = linkedDoctor()
        val profile = doctors.findByUserId(doctor.id).orElseThrow()
        assertThat(doctors.findAllByUserEnabled().map { it.id }).contains(profile.id)

        userRoles.remove(doctor, Role.DOCTOR) // what DoctorAccountDeletionService does for an account that is also a patient

        assertThat(doctor.role).isEqualTo(Role.PATIENT)
        assertThat(doctors.isListed(profile)).isFalse()
        assertThat(doctors.findAllByUserEnabled().map { it.id }).doesNotContain(profile.id)
        assertThat(doctors.searchWithFilters("Karimov", null).map { it.id }).doesNotContain(profile.id)
        val other = doctors.findByUserId(users.findByEmailIgnoreCase("seed.doctor2@shifa.dev").orElseThrow().id).orElseThrow()
        assertThat(doctors.isListed(other)).isTrue()
        assertThat(doctors.findAllByUserEnabled().map { it.id }).contains(other.id)
    }
}
