package com.shifa.auth

import com.shifa.ApplicationContextTest
import com.shifa.auth.api.AuthController
import com.shifa.auth.application.UserRoles
import com.shifa.auth.application.Users
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.UserRole
import com.shifa.shared.api.ErrorHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * The user's report (2026-11-11): a doctor who also has a patient account changed one password and the other changed
 * too. Against PostgreSQL with the dev seed: the patient app and the doctor app keep their own password once one side
 * changes it, and a patient-only account keeps one. Every test runs in a rolled-back transaction.
 */
@SpringBootTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "logging.file.name=build/shifa-backend-test.log",
        "app.dev-seed=true",
    ],
)
class PatientPasswordIntegrationTest {
    @Autowired lateinit var tx: PlatformTransactionManager
    @Autowired lateinit var auth: AuthController
    @Autowired lateinit var errorHandler: ErrorHandler
    @Autowired lateinit var users: Users
    @Autowired lateinit var userRoles: UserRoles
    @Autowired lateinit var userManagement: com.shifa.auth.application.UserManagementService

    companion object {
        @JvmStatic
        @BeforeAll
        fun requirePostgres() = ApplicationContextTest.requirePostgres()

        private const val SEED_PASSWORD = "Dev12345!"
    }

    private fun <T> rolledBack(block: () -> T): T =
        TransactionTemplate(tx).execute { status -> block().also { status.setRollbackOnly() } }!!

    /** The real controller; `reset-password`'s principal is the account, its app side comes from the token. */
    private fun mvc(userId: Long): MockMvc {
        val principal = org.springframework.security.core.userdetails.User(userId.toString(), "", emptyList())
        val asUser = object : HandlerMethodArgumentResolver {
            override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == UserDetails::class.java
            override fun resolveArgument(p: MethodParameter, m: ModelAndViewContainer?, r: NativeWebRequest, f: WebDataBinderFactory?) = principal
        }
        return MockMvcBuilders.standaloneSetup(auth).setControllerAdvice(errorHandler).setCustomArgumentResolvers(asUser).build()
    }

    private fun MockMvc.login(email: String, app: String?, password: String): Pair<Int, String?> {
        val request = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"$email","password":"$password"}""")
        val response = perform(if (app == null) request else request.param("app", app)).andReturn().response
        val token = Regex(""""token"\s*:\s*"([^"]+)"""").find(response.contentAsString)?.groupValues?.get(1)
        return response.status to token
    }

    private fun MockMvc.changePassword(token: String, current: String, new: String): Int =
        perform(
            post("/api/auth/reset-password").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$current","newPassword":"$new"}"""),
        ).andReturn().response.status

    @Test
    fun `a doctor with a patient account keeps one password per app`() = rolledBack<Unit> {
        val email = "seed.doctor1@shifa.dev"
        val doctor = users.findByEmailIgnoreCase(email).orElseThrow()
        userRoles.save(UserRole(user = doctor, role = Role.PATIENT))
        val mvc = mvc(doctor.id)

        // Linked: both apps start with the shared password.
        val (status, patientToken) = mvc.login(email, "patient", SEED_PASSWORD)
        assertThat(status).isEqualTo(200)
        assertThat(mvc.changePassword(patientToken!!, SEED_PASSWORD, "Patient123!")).isEqualTo(200)

        assertThat(mvc.login(email, "patient", "Patient123!").first).isEqualTo(200)
        assertThat(mvc.login(email, "patient", SEED_PASSWORD).first).isEqualTo(401)
        val (doctorStatus, doctorToken) = mvc.login(email, "doctor", SEED_PASSWORD)
        assertThat(doctorStatus).isEqualTo(200) // the doctor app's password did not change
        assertThat(mvc.login(email, "doctor", "Patient123!").first).isEqualTo(401)

        // And the other way: the doctor app changes its own, the patient app keeps its.
        assertThat(mvc.changePassword(doctorToken!!, SEED_PASSWORD, "Doctor123!")).isEqualTo(200)
        assertThat(mvc.login(email, "doctor", "Doctor123!").first).isEqualTo(200)
        assertThat(mvc.login(email, "patient", "Patient123!").first).isEqualTo(200)
        // The current password is checked against the caller's own side.
        assertThat(mvc.changePassword(doctorToken, "Patient123!", "Other123!")).isEqualTo(400)

        // An admin reset gives both apps the one temporary password.
        val temporary = userManagement.resetPassword(doctor.id)
        assertThat(mvc.login(email, "patient", temporary).first).isEqualTo(200)
        assertThat(mvc.login(email, "patient", "Patient123!").first).isEqualTo(401)
    }

    @Test
    fun `a patient who is also staff signs in without an app hint with the patient password only`() = rolledBack<Unit> {
        val email = "seed.patient1@shifa.dev"
        val patient = users.findByEmailIgnoreCase(email).orElseThrow()
        userRoles.save(UserRole(user = patient, role = Role.CLINIC_STAFF))
        val mvc = mvc(patient.id)
        val (_, token) = mvc.login(email, "patient", SEED_PASSWORD)
        assertThat(mvc.changePassword(token!!, SEED_PASSWORD, "Patient123!")).isEqualTo(200)
        // No app: the token falls back to the account's own role (PATIENT), so the patient password decides.
        assertThat(mvc.login(email, null, SEED_PASSWORD).first).isEqualTo(401)
        assertThat(mvc.login(email, null, "Patient123!").first).isEqualTo(200)
    }

    @Test
    fun `a patient-only account keeps one password`() = rolledBack<Unit> {
        val email = "seed.patient1@shifa.dev"
        val patient = users.findByEmailIgnoreCase(email).orElseThrow()
        val mvc = mvc(patient.id)
        val (_, token) = mvc.login(email, "patient", SEED_PASSWORD)
        assertThat(mvc.changePassword(token!!, SEED_PASSWORD, "Patient123!")).isEqualTo(200)
        assertThat(mvc.login(email, "patient", "Patient123!").first).isEqualTo(200)
        assertThat(users.findById(patient.id).orElseThrow().patientPasswordHash).isNull()
    }
}
