package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.Notification
import com.shifa.i18n.PatientPaymentPushI18n
import com.shifa.i18n.SmsReminderFormatting
import com.shifa.repo.AppointmentRepository
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.NotificationRepository
import com.shifa.repo.PatientProphylaxisSettingRepository
import com.shifa.repo.TaskCheckInRepository
import com.shifa.repo.TreatmentPlanLineRepository
import com.shifa.repo.TreatmentPlanPaymentRepository
import com.shifa.repo.TreatmentPlanRepository
import com.shifa.repo.InstallmentItemRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Task reminders: schedule is in patient's timezone; reminder window is in patient's "now".
 * Appointment reminders: UTC.
 */
@Service
class ReminderNotificationService(
    private val taskCheckInRepository: TaskCheckInRepository,
    private val notificationRepository: NotificationRepository,
    private val appointmentRepository: AppointmentRepository,
    private val doctorProfileRepository: DoctorProfileRepository,
    private val fcmService: FcmService,
    private val treatmentPlans: TreatmentPlanRepository,
    private val treatmentPlanLines: TreatmentPlanLineRepository,
    private val treatmentPlanPayments: TreatmentPlanPaymentRepository,
    private val prophylaxisSettings: PatientProphylaxisSettingRepository,
    private val installmentItems: InstallmentItemRepository,
    private val installmentService: InstallmentService,
    private val devSmsService: DevSmsService,
    private val doctorSmsBillingService: DoctorSmsBillingService,
) {
    private val log = LoggerFactory.getLogger(ReminderNotificationService::class.java)

    /** Task reminder window: 4–6 minutes from now in patient's timezone (fire at ~5 min before scheduled time). */
    private val taskReminderMinutesFrom = 4
    private val taskReminderMinutesTo = 6

    /** Appointment reminder window: 55–65 minutes from now (fire at ~1 hour before). */
    private val appointmentReminderMinutesFrom = 55
    private val appointmentReminderMinutesTo = 65

    /**
     * Pending video payment reminders: appointment starts ~[offset] from now.
     * Symmetric minute tolerance so a 60s scheduler run hits the window once.
     */
    private val paymentDueToleranceMinutes = 10L

    /** DevSMS SMS: patients with [PatientProfile.smsReminderHoursBefore] of 24 or 1 (same timing rules). */
    private val smsReminderHourOptions = listOf(24L, 1L)

    @Transactional
    fun sendTaskReminders() {
        val utcToday = LocalDate.now(ZoneOffset.UTC)
        val pending = taskCheckInRepository.findPendingForReminderWithTaskAndPatientInDateRange(
            utcToday.minusDays(1),
            utcToday.plusDays(1),
        )
        for (checkIn in pending) {
            val task = checkIn.task
            val patient = task.patient
            val patientTz = patient.timeZone?.takeIf { it.isNotBlank() } ?: "UTC"
            val zone = ZoneId.of(patientTz)
            val now = ZonedDateTime.now(zone)
            val date = now.toLocalDate()
            val timeFrom = now.toLocalTime().plusMinutes(taskReminderMinutesFrom.toLong()).truncatedTo(ChronoUnit.MINUTES)
            val timeTo = now.toLocalTime().plusMinutes(taskReminderMinutesTo.toLong()).truncatedTo(ChronoUnit.MINUTES)
            if (checkIn.scheduledDate != date) continue
            val st = checkIn.scheduledTime ?: continue
            if (st.isBefore(timeFrom) || st.isAfter(timeTo)) continue
            try {
                val title = "Task reminder"
                val message = "${task.taskName} is scheduled at ${checkIn.scheduledTime}. Please complete when ready."
                val notification = Notification(
                    patient = patient,
                    doctor = null,
                    title = title,
                    message = message,
                    type = Notification.Type.TASK_REMINDER,
                    appointmentId = null,
                    documentAccessRequestId = null,
                    taskId = task.id,
                )
                val saved = notificationRepository.save(notification)
                patient.fcmToken?.let { token ->
                    fcmService.sendPatientNotification(token, saved)
                }
                checkIn.reminderSentAt = Instant.now()
                taskCheckInRepository.save(checkIn)
                log.info("Task reminder sent for checkIn id={} task={}", checkIn.id, task.id)
            } catch (e: Exception) {
                log.warn("Task reminder failed for checkIn id={}: {}", checkIn.id, e.message)
            }
        }
    }

    @Transactional
    fun sendAppointmentReminders() {
        val now = Instant.now()
        val windowStart = now.plus(appointmentReminderMinutesFrom.toLong(), ChronoUnit.MINUTES)
        val windowEnd = now.plus(appointmentReminderMinutesTo.toLong(), ChronoUnit.MINUTES)
        val appointments = appointmentRepository.findAppointmentsStartingBetween(windowStart, windowEnd)
        for (appointment in appointments) {
            try {
                // Unpaid video: ~1h payment reminder (CONSULTATION_PAYMENT_DUE_1H) already nudges checkout;
                // skip generic "be ready" to avoid duplicate / confusing pushes.
                val isVideo = appointment.location.lowercase().contains("video")
                if (isVideo && appointment.paymentStatus == Appointment.PaymentStatus.PENDING) {
                    continue
                }
                val patient = appointment.patient
                val existing = notificationRepository.findByPatient_IdAndAppointmentIdAndType(
                    patient.id!!,
                    appointment.id,
                    Notification.Type.APPOINTMENT_REMINDER
                )
                if (existing.isNotEmpty()) continue
                val title = "Appointment reminder"
                val message = "Your appointment is in about 1 hour. Please be ready."
                val notification = Notification(
                    patient = patient,
                    doctor = null,
                    title = title,
                    message = message,
                    type = Notification.Type.APPOINTMENT_REMINDER,
                    appointmentId = appointment.id,
                    documentAccessRequestId = null,
                    taskId = null,
                )
                val saved = notificationRepository.save(notification)
                patient.fcmToken?.let { token ->
                    fcmService.sendPatientNotification(token, saved)
                }
                log.info("Appointment reminder sent for appointment id={} patient={}", appointment.id, patient.id)
            } catch (e: Exception) {
                log.warn("Appointment reminder failed for appointment id={}: {}", appointment.id, e.message)
            }
        }
    }

    /**
     * DevSMS: send one text before appointment start for patients with [PatientProfile.smsReminderEnabled].
     * Timing follows [PatientProfile.smsReminderHoursBefore] (1 or 24 hours).
     */
    @Transactional
    fun sendAppointmentSmsReminders() {
        if (!devSmsService.isConfigured()) {
            log.debug("SMS reminder scheduler skip: DevSMS not configured")
            return
        }
        val now = Instant.now()
        for (hoursBefore in smsReminderHourOptions) {
            val horizonEnd = SmsReminderTiming.reminderHorizonEnd(
                now,
                hoursBefore,
                paymentDueToleranceMinutes,
            )
            val appointments = appointmentRepository.findAppointmentsForSmsReminder(
                now,
                horizonEnd,
                hoursBefore.toInt(),
            )
            if (appointments.isNotEmpty()) {
                log.info(
                    "SMS reminder scan: hoursBefore={} candidates={} horizonEnd={}",
                    hoursBefore,
                    appointments.size,
                    horizonEnd,
                )
            }
            sendAppointmentSmsRemindersForBatch(appointments, now, hoursBefore)
        }
    }

    private fun sendAppointmentSmsRemindersForBatch(
        appointments: List<Appointment>,
        now: Instant,
        hoursBefore: Long,
    ) {
        for (appointment in appointments) {
            try {
                if (!SmsReminderTiming.isDue(now, appointment.startAt, hoursBefore, paymentDueToleranceMinutes)) {
                    continue
                }
                val patient = appointment.patient
                if (!patient.smsReminderEnabled) continue
                val phone = patient.phoneNormalized ?: patient.phone
                if (phone.isNullOrBlank()) {
                    log.warn("SMS reminder skip: no phone for patient id={}", patient.id)
                    continue
                }
                val doctor = appointment.doctor
                val doctorName = "${doctor.firstName} ${doctor.lastName}".trim()
                val zone = doctor.timeZone?.takeIf { it.isNotBlank() } ?: "UTC"
                val message = SmsReminderFormatting.appointmentReminderBody(
                    patientName = patient.fullName,
                    doctorName = doctorName,
                    startAt = appointment.startAt,
                    timeZone = zone,
                )
                val sendResult = devSmsService.sendSms(phone, message)
                if (sendResult.success) {
                    doctorSmsBillingService.recordSentSms(
                        doctor = doctor,
                        patient = patient,
                        appointmentId = appointment.id,
                        devsmsSmsId = sendResult.smsId,
                    )
                    appointment.smsReminderSentAt = Instant.now()
                    appointmentRepository.save(appointment)
                    log.info("SMS reminder sent for appointment id={} patient={}", appointment.id, patient.id)
                } else {
                    log.warn(
                        "SMS reminder DevSMS failed for appointment id={}: {}",
                        appointment.id,
                        sendResult.errorMessage ?: "unknown error",
                    )
                }
            } catch (e: Exception) {
                log.warn("SMS reminder failed for appointment id={}: {}", appointment.id, e.message)
            }
        }
    }

    /**
     * Automated payment reminders for video consultations that are still unpaid.
     * Fires once per tier: ~24h, ~6h, and ~1h before [startAt] (UTC windows).
     */
    @Transactional
    fun sendPendingConsultationPaymentReminders() {
        val now = Instant.now()
        val tiers = listOf(
            Triple(24L, Notification.Type.CONSULTATION_PAYMENT_DUE_24H, 24),
            Triple(6L, Notification.Type.CONSULTATION_PAYMENT_DUE_6H, 6),
            Triple(1L, Notification.Type.CONSULTATION_PAYMENT_DUE_1H, 1),
        )
        for ((hoursOffset, type, hoursForCopy) in tiers) {
            val center = now.plus(hoursOffset, ChronoUnit.HOURS)
            val windowStart = center.minus(paymentDueToleranceMinutes, ChronoUnit.MINUTES)
            val windowEnd = center.plus(paymentDueToleranceMinutes, ChronoUnit.MINUTES)
            val appointments =
                appointmentRepository.findPendingPaymentVideoAppointmentsStartingBetween(windowStart, windowEnd)
            for (appointment in appointments) {
                try {
                    val patient = appointment.patient
                    val patientId = patient.id ?: continue
                    val existing = notificationRepository.findByPatient_IdAndAppointmentIdAndType(
                        patientId,
                        appointment.id,
                        type
                    )
                    if (existing.isNotEmpty()) continue

                    val lang = patient.language
                    val title = PatientPaymentPushI18n.paymentTitle(lang)
                    val message = PatientPaymentPushI18n.paymentDueBody(lang, hoursForCopy)
                    val notification = Notification(
                        patient = patient,
                        doctor = null,
                        title = title,
                        message = message,
                        type = type,
                        appointmentId = appointment.id,
                        documentAccessRequestId = null,
                        taskId = null,
                    )
                    val saved = notificationRepository.save(notification)
                    patient.fcmToken?.let { token ->
                        fcmService.sendPatientNotification(
                            token,
                            saved,
                            mapOf("route" to "/bookings/${appointment.id}/pay")
                        )
                    }
                    log.info(
                        "Pending payment reminder ({}) sent for appointment id={} patient={}",
                        type.name,
                        appointment.id,
                        patientId
                    )
                } catch (e: Exception) {
                    log.warn(
                        "Pending payment reminder failed for appointment id={} type={}: {}",
                        appointment.id,
                        type.name,
                        e.message
                    )
                }
            }
        }
    }

    /**
     * Outstanding balances on active treatment plans — sends at most once per 7 days
     * (or per [paymentReminderDays] if set). Uses denormalized [remainingAmountMinor]
     * and [lastPaymentReminderSentAt] for efficiency. Also covers overdue installment items.
     */
    @Transactional
    fun sendTreatmentPlanPaymentReminders() {
        val now = Instant.now()
        val activePlans = treatmentPlans.findActivePlansEligibleForPaymentReminder(
            listOf(
                com.shifa.domain.TreatmentPlan.Status.ACTIVE,
                com.shifa.domain.TreatmentPlan.Status.IN_PROGRESS,
            ),
            // Minimum cooldown is 1 day; per-plan longer cooldowns are applied below.
            OffsetDateTime.now().minusDays(1),
        )
        for (plan in activePlans) {
            try {
                if (plan.remainingAmountMinor <= 0L) continue
                val cooldownDays = (plan.paymentReminderDays ?: 7).toLong().coerceAtLeast(1L)
                val lastSent = plan.lastPaymentReminderSentAt
                if (lastSent != null && lastSent.toInstant().isAfter(now.minus(cooldownDays, ChronoUnit.DAYS))) {
                    continue
                }
                val patient = plan.patient
                val pid = patient.id ?: continue
                val notification = Notification(
                    patient = patient,
                    doctor = null,
                    title = "Payment reminder",
                    message = "You have an outstanding balance on your treatment plan. Please arrange payment at the clinic.",
                    type = Notification.Type.TREATMENT_PLAN_PAYMENT_REMINDER,
                    treatmentPlanId = plan.id
                )
                val saved = notificationRepository.save(notification)
                patient.fcmToken?.let {
                    fcmService.sendPatientNotification(
                        it,
                        saved,
                        mapOf("route" to "/bookings/treatment-plan/${plan.id}")
                    )
                }
                plan.lastPaymentReminderSentAt = java.time.OffsetDateTime.now()
                treatmentPlans.save(plan)
                log.info("Treatment plan payment reminder sent planId={} patient={}", plan.id, pid)
            } catch (e: Exception) {
                log.warn("Treatment plan reminder failed plan id={}: {}", plan.id, e.message)
            }
        }
    }

    /** Recall / prophylaxis reminders anchored on last completed visit with doctors in the same clinic. */
    @Transactional
    fun sendProphylaxisReminders() {
        val settings = prophylaxisSettings.findAllByEnabledTrue()
        if (settings.isEmpty()) return
        val now = Instant.now()
        for ((clinicId, clinicSettings) in settings.groupBy { it.clinic.id }) {
            val doctorIds = doctorProfileRepository.findAllByPracticeClinic_Id(clinicId).mapNotNull { it.id }
            if (doctorIds.isEmpty()) continue
            for (st in clinicSettings) {
                try {
                    sendProphylaxisReminderIfDue(st, doctorIds, now)
                } catch (e: Exception) {
                    log.warn("Prophylaxis reminder failed setting id={}: {}", st.id, e.message)
                }
            }
        }
    }

    private fun sendProphylaxisReminderIfDue(
        st: com.shifa.domain.PatientProphylaxisSetting,
        doctorIds: List<Long>,
        now: Instant,
    ) {
        val clinicId = st.clinic.id
        val patientId = st.patient.id ?: return
        val latest = appointmentRepository.findCompletedForPatientAmongDoctors(
            patientId,
            doctorIds,
            PageRequest.of(0, 1),
        ).firstOrNull() ?: return
        val anchor = latest.endAt
        val due = anchor.atZone(ZoneOffset.UTC).toLocalDate()
            .plusMonths(st.intervalMonths.toLong())
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
        if (due.isAfter(now)) return
        val lastSent = st.lastSentAt?.toInstant()
        if (lastSent != null && lastSent.isAfter(now.minus(14, ChronoUnit.DAYS))) return
        val notification = Notification(
            patient = st.patient,
            doctor = null,
            title = "Time for a visit",
            message = "It has been more than ${st.intervalMonths} months since your last completed visit. Please book an appointment with your clinic.",
            type = Notification.Type.PROPHYLAXIS_REMINDER,
            treatmentPlanId = null,
        )
        val saved = notificationRepository.save(notification)
        st.patient.fcmToken?.let {
            fcmService.sendPatientNotification(
                it,
                saved,
                mapOf("route" to "/bookings"),
            )
        }
        st.lastSentAt = OffsetDateTime.now()
        prophylaxisSettings.save(st)
        log.info("Prophylaxis reminder sent patient={} clinic={}", patientId, clinicId)
    }

    @Transactional
    fun sendInstallmentDueReminders() {
        val today = LocalDate.now(ZoneOffset.UTC)
        installmentService.markPendingInstallmentsOverdue(today)

        fun sendIfNew(
            item: com.shifa.domain.InstallmentItem,
            type: Notification.Type,
            title: String,
            message: String,
        ) {
            val patient = item.installmentPlan.treatmentPlan.patient
            val pid = patient.id ?: return
            if (notificationRepository.existsByPatient_IdAndInstallmentItemIdAndType(pid, item.id, type)) return
            val tp = item.installmentPlan.treatmentPlan
            val n = Notification(
                patient = patient,
                doctor = null,
                title = title,
                message = message,
                type = type,
                treatmentPlanId = tp.id,
                installmentItemId = item.id,
            )
            val saved = notificationRepository.save(n)
            patient.fcmToken?.let {
                fcmService.sendPatientNotification(
                    it,
                    saved,
                    mapOf(
                        "route" to "/bookings/treatment-plan/${tp.id}",
                        "installmentItemId" to item.id.toString(),
                    ),
                )
            }
            item.lastReminderSentAt = OffsetDateTime.now()
            installmentItems.save(item)
        }

        val soon = today.plusDays(3)
        for (item in installmentItems.findAllPendingDueOn(soon)) {
            try {
                sendIfNew(
                    item,
                    Notification.Type.INSTALLMENT_DUE_SOON,
                    "Installment coming up",
                    "An installment of ${item.amountMinor / 100.0} ${item.currency} is due on ${item.dueDate}.",
                )
            } catch (e: Exception) {
                log.warn("Installment due soon failed item={}: {}", item.id, e.message)
            }
        }
        for (item in installmentItems.findAllPendingDueOn(today)) {
            try {
                sendIfNew(
                    item,
                    Notification.Type.INSTALLMENT_DUE_TODAY,
                    "Installment due today",
                    "An installment of ${item.amountMinor / 100.0} ${item.currency} is due today.",
                )
            } catch (e: Exception) {
                log.warn("Installment due today failed item={}: {}", item.id, e.message)
            }
        }
        val overdueBefore = OffsetDateTime.now().minusDays(7)
        for (item in installmentItems.findOverdueNeedingRemind(overdueBefore)) {
            try {
                sendIfNew(
                    item,
                    Notification.Type.INSTALLMENT_OVERDUE,
                    "Installment overdue",
                    "An installment of ${item.amountMinor / 100.0} ${item.currency} is overdue. Please contact your clinic.",
                )
            } catch (e: Exception) {
                log.warn("Installment overdue notify failed item={}: {}", item.id, e.message)
            }
        }
    }
}
