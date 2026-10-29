package com.shifa.devseed

import com.shifa.admin.domain.AdminLevel
import com.shifa.admin.domain.AdminProfile
import com.shifa.appointments.domain.Appointment
import com.shifa.appointments.domain.ScheduleValidityPeriod
import com.shifa.appointments.domain.WeeklyScheduleRule
import com.shifa.auth.domain.Role
import com.shifa.auth.domain.SubscriptionTier
import com.shifa.auth.domain.User
import com.shifa.auth.domain.UserRole
import com.shifa.clinics.domain.Clinic
import com.shifa.clinics.domain.ClinicMembership
import com.shifa.doctors.domain.DoctorLocation
import com.shifa.doctors.domain.DoctorProfile
import com.shifa.doctors.domain.DoctorService
import com.shifa.doctors.domain.DoctorServicePrice
import com.shifa.doctors.domain.ServicePaymentType
import com.shifa.documents.application.PatientDocumentStorageService
import com.shifa.documents.domain.PatientDocument
import com.shifa.documents.domain.PatientDocumentCategory
import com.shifa.notifications.domain.Notification
import com.shifa.patients.domain.PatientProfile
import com.shifa.shared.util.PhoneNormalizer
import com.shifa.tasks.domain.RemoteCareTask
import com.shifa.tasks.domain.TaskCheckIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Deterministic test data for the development environment and local runs (roadmap 1.7). Enabled only with
 * `app.dev-seed=true` (application-dev.yml, application-local.yml) and additionally refused when the `prod`
 * profile is active without `dev`, so a stray variable cannot seed production. Idempotent: it does nothing
 * when the first seed doctor already exists.
 *
 * Accounts: seed.doctor1..3@shifa.dev, seed.staff@shifa.dev, seed.patient1..3@shifa.dev (phones
 * +99890000010N), all with password [PASSWORD]. Patients 4 and 5 are doctor-created profiles without a login.
 *
 * Admins: one per address in `app.dev-seed-admin-emails` (comma-separated), because admin sign-in needs the e-mail
 * second factor (POST /api/auth/admin/request-login-otp), so each must be a real mailbox. Same password. Seeded even
 * when the rest of the data already exists, so they can be added to a running environment.
 */
@Component
@ConditionalOnProperty("app.dev-seed", havingValue = "true")
class DevSeedRunner(
    private val users: com.shifa.auth.application.Users,
    private val userRoles: com.shifa.auth.application.UserRoles,
    private val doctors: com.shifa.doctors.application.DoctorProfiles,
    private val locations: com.shifa.doctors.application.DoctorLocations,
    private val rules: com.shifa.appointments.application.WeeklyScheduleRules,
    private val validityPeriods: com.shifa.appointments.application.ScheduleValidityPeriods,
    private val services: com.shifa.doctors.application.DoctorServices,
    private val prices: com.shifa.doctors.application.DoctorServicePrices,
    private val clinics: com.shifa.clinics.application.Clinics,
    private val memberships: com.shifa.clinics.application.ClinicMemberships,
    private val patients: com.shifa.patients.application.PatientProfiles,
    private val appointments: com.shifa.appointments.application.Appointments,
    private val documents: com.shifa.documents.application.PatientDocumentService,
    private val tasks: com.shifa.tasks.application.RemoteCareTasks,
    private val checkIns: com.shifa.tasks.application.TaskCheckIns,
    private val notificationService: com.shifa.notifications.application.NotificationService,
    private val passwordEncoder: PasswordEncoder,
    private val documentStorage: PatientDocumentStorageService,
    private val adminProfiles: com.shifa.admin.application.AdminProfiles,
    private val environment: Environment,
    @org.springframework.beans.factory.annotation.Value("\${app.dev-seed-admin-emails:}") private val adminEmails: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(DevSeedRunner::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val profiles = environment.activeProfiles.toSet()
        if ("prod" in profiles && "dev" !in profiles) {
            log.error("app.dev-seed=true on a production profile: refusing to seed")
            return
        }
        val hash = passwordEncoder.encode(PASSWORD)
        seedAdmins(hash)
        if (users.findByEmail(doctorEmail(1)).isPresent) {
            ensureValidityPeriods()
            log.info("Dev seed already present, nothing to do")
            return
        }
        log.warn("Seeding development data (app.dev-seed=true)")

        val clinic = clinics.save(Clinic(name = "Shifa Dev Clinic", phone = "+998712000000", email = "clinic@shifa.dev", address = "Amir Temur 1, Tashkent"))
        val doctorRows = listOf(
            Triple("Ulugbek", "Karimov", "Cardiologist") to SubscriptionTier.PREMIUM,
            Triple("Dilnoza", "Rashidova", "Dentist") to SubscriptionTier.PRO,
            Triple("Bekzod", "Tashkentov", "Pediatrician") to SubscriptionTier.BASIC,
        ).mapIndexed { i, (name, tier) ->
            val n = i + 1
            val user = users.save(User(email = doctorEmail(n), phone = "+99890000020$n", passwordHash = hash, role = Role.DOCTOR, subscriptionTier = tier))
            userRoles.save(UserRole(user = user, role = Role.DOCTOR))
            val doctor = doctors.save(
                DoctorProfile(
                    user = user, firstName = name.first, lastName = name.second, profession = name.third,
                    clinic = clinic.name, address = clinic.address, locationCity = "Tashkent", locationCountry = "Uzbekistan",
                    practiceClinic = clinic, consultationPriceMinor = 150_000L * n, consultationCurrency = "UZS",
                    biography = "Seed doctor $n for the development environment.",
                ),
            )
            memberships.save(ClinicMembership(clinic = clinic, user = user, membershipRole = if (n == 1) ClinicMembership.MembershipRole.OWNER else ClinicMembership.MembershipRole.DOCTOR, doctorProfile = doctor))
            val location = locations.save(DoctorLocation(doctor = doctor, label = clinic.name, clinic = clinic.name, address = clinic.address, locationCity = "Tashkent", locationCountry = "Uzbekistan", isPrimary = true))
            for (weekday in DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value) {
                rules.save(WeeklyScheduleRule(doctor = doctor, location = location, weekday = weekday, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0), slotMinutes = 30))
            }
            validityPeriods.save(validityPeriodFor(doctor))
            services.save(DoctorService(doctor = doctor, title = "Free first consultation", isFreeConsultation = true, paymentType = ServicePaymentType.PAY_LATER))
            val payLater = services.save(DoctorService(doctor = doctor, title = "Follow-up visit", paymentType = ServicePaymentType.PAY_LATER))
            prices.save(DoctorServicePrice(service = payLater, location = location, amountMinor = 100_000L * n, currency = "UZS"))
            val payNow = services.save(DoctorService(doctor = doctor, title = "Consultation", paymentType = ServicePaymentType.PAY_NOW))
            prices.save(DoctorServicePrice(service = payNow, location = location, amountMinor = 150_000L * n, currency = "UZS"))
            Triple(doctor, location, payNow)
        }
        val staff = users.save(User(email = "seed.staff@shifa.dev", phone = "+998900000300", passwordHash = hash, role = Role.CLINIC_STAFF, staffFirstName = "Nilufar", staffLastName = "Abdullaeva"))
        userRoles.save(UserRole(user = staff, role = Role.CLINIC_STAFF))
        memberships.save(ClinicMembership(clinic = clinic, user = staff, membershipRole = ClinicMembership.MembershipRole.STAFF))

        val (doctor1, location1, consultation) = doctorRows[0]
        val patientNames = listOf("Jasur Aliyev", "Gulnora Yusupova", "Sardor Ismoilov", "Malika Tursunova", "Aziz Rahimov")
        val patientRows = patientNames.mapIndexed { i, fullName ->
            val n = i + 1
            val phone = "+99890000010$n"
            val user = if (n <= 3) {
                // Patients are PRO or PREMIUM only (SubscriptionTierService rejects BASIC for patients).
                users.save(User(email = patientEmail(n), phone = phone, passwordHash = hash, role = Role.PATIENT, subscriptionTier = if (n == 1) SubscriptionTier.PREMIUM else SubscriptionTier.PRO))
                    .also { userRoles.save(UserRole(user = it, role = Role.PATIENT)) }
            } else {
                null
            }
            patients.save(
                PatientProfile(
                    fullName = fullName, phone = phone, phoneNormalized = PhoneNormalizer.normalize(phone),
                    email = if (user != null) patientEmail(n) else null, user = user,
                    createdByDoctor = if (user == null) doctor1 else null, birthDate = LocalDate.of(1980 + 5 * n, n, 10 + n),
                    gender = if (n % 2 == 0) "female" else "male", language = "uz", timeZone = "Asia/Tashkent",
                ),
            )
        }

        // One appointment per status for doctor 1, past ones completed, future ones pending.
        val zone = ZoneId.of("Asia/Tashkent")
        val today = LocalDate.now(zone)
        fun at(dayOffset: Long, hour: Int): Instant = today.plusDays(dayOffset).atTime(hour, 0).atZone(zone).toInstant()
        val statusPlan = listOf(
            Appointment.Status.COMPLETED to -7L, Appointment.Status.NO_SHOW to -5L, Appointment.Status.CANCELLED to -3L,
            Appointment.Status.EXPIRED to -2L, Appointment.Status.PAYMENT_FAILED to -1L, Appointment.Status.IN_PROGRESS to 0L,
            Appointment.Status.CONFIRMED to 1L, Appointment.Status.REQUESTED to 2L, Appointment.Status.PENDING_PAYMENT to 3L,
        )
        val seededAppointments = statusPlan.mapIndexed { i, (status, dayOffset) ->
            val patient = patientRows[i % patientRows.size]
            val video = i % 3 == 0
            val start = at(dayOffset, 10 + (i % 4))
            appointments.save(
                Appointment(
                    doctor = doctor1, patient = patient, bookedByUser = patient.user, startAt = start, endAt = start.plus(30, ChronoUnit.MINUTES),
                    location = if (video) "Video Consultation" else location1.label, locationRef = if (video) null else location1,
                    isVideoConsultation = video, reason = "Seed: ${status.name.lowercase().replace('_', ' ')}", status = status,
                    serviceId = consultation.id, serviceTitle = consultation.title, paymentType = ServicePaymentType.PAY_NOW,
                    paymentAmountMinor = 150_000L, paymentCurrency = "UZS",
                    paymentStatus = when (status) {
                        Appointment.Status.COMPLETED, Appointment.Status.CONFIRMED -> Appointment.PaymentStatus.PAID
                        Appointment.Status.PENDING_PAYMENT -> Appointment.PaymentStatus.PENDING
                        Appointment.Status.PAYMENT_FAILED -> Appointment.PaymentStatus.FAILED
                        else -> Appointment.PaymentStatus.NOT_REQUIRED
                    },
                ),
            )
        }
        // Patients 2 and 3 also see doctors 2 and 3.
        appointments.save(Appointment(doctor = doctorRows[1].first, patient = patientRows[1], startAt = at(4, 11), endAt = at(4, 11).plus(30, ChronoUnit.MINUTES), location = doctorRows[1].second.label, locationRef = doctorRows[1].second, status = Appointment.Status.CONFIRMED))
        appointments.save(Appointment(doctor = doctorRows[2].first, patient = patientRows[2], startAt = at(5, 9), endAt = at(5, 9).plus(30, ChronoUnit.MINUTES), location = doctorRows[2].second.label, locationRef = doctorRows[2].second, status = Appointment.Status.REQUESTED))

        // Documents for patient 1: one uploaded by the doctor and shared with the team, one by the patient.
        val patient1 = patientRows[0]
        val patient1Id = patient1.id!!
        val bloodTest = documentStorage.saveBytes(patient1Id, pdf("Seed blood test"), "seed-blood-test")
        val ecg = documentStorage.saveBytes(patient1Id, pdf("Seed ECG"), "seed-ecg")
        documents.save(PatientDocument(patient = patient1, title = "Blood test", date = today.minusDays(7), filePath = bloodTest.filePathRelative, uploadedByDoctor = doctor1, category = PatientDocumentCategory.BLOOD_TEST.code, isSharedWithTeam = true, appointment = seededAppointments[0]))
        documents.save(PatientDocument(patient = patient1, title = "ECG from previous clinic", date = today.minusDays(30), filePath = ecg.filePathRelative, uploadedByPatientProfile = patient1, category = PatientDocumentCategory.ECG.code))

        // Remote-care tasks with check-ins for the last three days.
        val bloodPressure = tasks.save(RemoteCareTask(doctor = doctor1, patient = patient1, taskName = "Blood pressure", description = "Measure every morning", category = RemoteCareTask.Category.VITAL, timesPerDay = 1, morningTime = LocalTime.of(8, 0), startDate = today.minusDays(3), durationDays = 14, inputType = RemoteCareTask.InputType.NUMERIC, inputLabel = "Systolic (mmHg)"))
        val medication = tasks.save(RemoteCareTask(doctor = doctor1, patient = patient1, taskName = "Evening medication", category = RemoteCareTask.Category.MEDICATION, timesPerDay = 1, eveningTime = LocalTime.of(20, 0), startDate = today.minusDays(3), durationDays = 14, inputType = RemoteCareTask.InputType.BOOLEAN))
        for (d in 3L downTo 0L) {
            val day = today.minusDays(d)
            checkIns.save(TaskCheckIn(task = bloodPressure, scheduledDate = day, scheduledTime = LocalTime.of(8, 0), status = if (d == 0L) TaskCheckIn.Status.PENDING else TaskCheckIn.Status.COMPLETED, numericValue = if (d == 0L) null else 120.0 + d, completedAt = if (d == 0L) null else day.atTime(8, 15).atZone(zone).toInstant()))
            checkIns.save(TaskCheckIn(task = medication, scheduledDate = day, scheduledTime = LocalTime.of(20, 0), status = when (d) { 0L -> TaskCheckIn.Status.PENDING; 2L -> TaskCheckIn.Status.MISSED; else -> TaskCheckIn.Status.COMPLETED }, booleanValue = if (d == 0L || d == 2L) null else true))
        }

        // One notification of every type, for doctor 1 and for patient 1.
        Notification.Type.entries.forEach { type ->
            val title = type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            seedNotification(type, title, doctor = doctor1, appointmentId = seededAppointments[0].id, taskId = bloodPressure.id)
            seedNotification(type, title, patient = patient1, appointmentId = seededAppointments[0].id, taskId = bloodPressure.id)
        }
        log.warn("Dev seed done: 3 doctors, 1 staff, 5 patients, ${statusPlan.size + 2} appointments, 2 documents, 2 tasks, ${Notification.Type.entries.size * 2} notifications")
    }

    private fun seedNotification(
        type: Notification.Type,
        title: String,
        doctor: DoctorProfile? = null,
        patient: PatientProfile? = null,
        appointmentId: Long?,
        taskId: Long?,
    ) {
        notificationService.save(
            Notification(
                doctor = doctor, patient = patient, title = title, message = "Seed notification: $title", type = type,
                appointmentId = appointmentId, taskId = taskId,
            ),
        )
    }

    /** ADMIN logins for the admin panel and the admin API (e.g. the storage migration), one per configured address. */
    private fun seedAdmins(hash: String) {
        val emails = adminEmails.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        for (email in emails) {
            if (users.findByEmailIgnoreCase(email).isPresent) continue // login resolves addresses case-insensitively too
            val admin = users.save(User(email = email, passwordHash = hash, role = Role.ADMIN))
            userRoles.save(UserRole(user = admin, role = Role.ADMIN))
            adminProfiles.save(AdminProfile(user = admin, firstName = "Seed", lastName = "Admin", adminLevel = AdminLevel.SUPER_ADMIN))
            log.warn("Seeded admin account {} (password: the dev seed password)", email.take(3) + "***")
        }
    }

    /** Environments seeded before validity periods were part of the seed: give each seed doctor one, once. */
    private fun ensureValidityPeriods() {
        listOf(1, 2, 3)
            .mapNotNull { n -> users.findByEmail(doctorEmail(n)).orElse(null) }
            .mapNotNull { user -> doctors.findByUserId(user.id).orElse(null) }
            .filter { doctor -> doctor.id != null && validityPeriods.findByDoctorIdOrderByValidFromAsc(doctor.id!!).isEmpty() }
            .forEach { doctor ->
                validityPeriods.save(validityPeriodFor(doctor))
                log.info("Dev seed: added a schedule validity period for seed doctor {}", doctor.id)
            }
    }

    companion object {
        const val PASSWORD = "Dev12345!"

        /** A minimal one-page PDF showing [text], so seeded documents open in the apps. */
        fun pdf(text: String): ByteArray {
            val stream = "BT /F1 18 Tf 72 720 Td ($text) Tj ET"
            val objects = listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            )
            val out = StringBuilder("%PDF-1.4\n")
            val offsets = objects.mapIndexed { i, body ->
                val offset = out.length
                out.append("${i + 1} 0 obj\n$body\nendobj\n")
                offset
            }
            val xref = out.length
            out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { out.append(String.format("%010d 00000 n \n", it)) }
            out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return out.toString().toByteArray(Charsets.ISO_8859_1)
        }
        fun doctorEmail(n: Int) = "seed.doctor$n@shifa.dev"

        /** Slots exist only inside a validity period (ScheduleValidityService): one open period per seed doctor. */
        fun validityPeriodFor(doctor: DoctorProfile) = ScheduleValidityPeriod(
            doctor = doctor,
            validFrom = LocalDate.now().minusDays(30),
            validUntil = LocalDate.now().plusYears(1),
        )
        fun patientEmail(n: Int) = "seed.patient$n@shifa.dev"
    }
}
