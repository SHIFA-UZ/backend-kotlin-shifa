package com.shifa.service

import com.shifa.domain.Appointment
import com.shifa.domain.Role
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Loads per-doctor activity counters in a fixed number of GROUP BY queries for the admin list screen.
 * Avoids N+1 repository calls (previously ~20 queries × doctor count per page load).
 */
@Component
class AdminDoctorActivityMetricsBatchLoader(
    @PersistenceContext private val em: EntityManager,
) {

    data class DoctorRowMetrics(
        val appointmentsBooked: Long = 0,
        val completed: Long = 0,
        val cancelled: Long = 0,
        val inProgress: Long = 0,
        val video: Long = 0,
        val activePatients: Long = 0,
        val patientsCreated: Long = 0,
        val documentsUploaded: Long = 0,
        val treatmentPlans: Long = 0,
        val remoteTasks: Long = 0,
        val consultationNotes: Long = 0,
        val patientForms: Long = 0,
        val aiDraftNotes: Long = 0,
        val lastActiveAt: Instant? = null,
    )

    data class BatchResult(
        val byDoctorId: Map<Long, DoctorRowMetrics>,
        val aiRequestsByUserId: Map<Long, Long>,
        val maxAiUsageDateByUserId: Map<Long, LocalDate>,
    )

    fun load(
        doctorIds: Collection<Long>,
        userIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ): BatchResult {
        val ids = doctorIds.distinct()
        val uids = userIds.distinct()
        if (ids.isEmpty() && uids.isEmpty()) {
            return BatchResult(emptyMap(), emptyMap(), emptyMap())
        }
        val byDoctor = if (ids.isEmpty()) emptyMap() else mergeDoctorMetrics(ids, window)
        return BatchResult(
            byDoctorId = byDoctor,
            aiRequestsByUserId = loadAiRequestsByUserId(uids, window),
            maxAiUsageDateByUserId = loadMaxAiUsageDateByUserId(uids, window),
        )
    }

    private fun mergeDoctorMetrics(
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ): Map<Long, DoctorRowMetrics> {
        val builders = doctorIds.associateWith { DoctorMetricsBuilder() }

        applyAppointmentStatusCounts(builders, doctorIds, window)
        applyVideoCounts(builders, doctorIds, window)
        applyActivePatientCounts(builders, doctorIds, window)
        applyPatientsCreatedCounts(builders, doctorIds, window)
        applyDocumentsUploadedCounts(builders, doctorIds, window)
        applyTreatmentPlanCounts(builders, doctorIds, window)
        applyRemoteTaskCounts(builders, doctorIds, window)
        applyConsultationNoteCounts(builders, doctorIds, window)
        applyPatientFormCounts(builders, doctorIds, window)
        applyAiDraftNoteCounts(builders, doctorIds, window)
        applyLastActive(builders, doctorIds)

        return builders.mapValues { (_, b) -> b.toMetrics() }
    }

    private fun applyAppointmentStatusCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT a.doctor.id, a.status, COUNT(a) FROM Appointment a
                   WHERE a.doctor.id IN :ids AND a.startAt >= :start AND a.startAt < :end
                   GROUP BY a.doctor.id, a.status""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("start", window.startInstant)
                .setParameter("end", window.endExclusiveInstant)
        } else {
            em.createQuery(
                """SELECT a.doctor.id, a.status, COUNT(a) FROM Appointment a
                   WHERE a.doctor.id IN :ids GROUP BY a.doctor.id, a.status""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        for (row in q.resultList) {
            val doctorId = (row[0] as Number).toLong()
            val status = row[1] as Appointment.Status
            val count = (row[2] as Number).toLong()
            val b = builders[doctorId] ?: continue
            b.appointmentsBooked += count
            when (status) {
                Appointment.Status.COMPLETED -> b.completed = count
                Appointment.Status.CANCELLED -> b.cancelled = count
                Appointment.Status.IN_PROGRESS -> b.inProgress = count
                else -> {}
            }
        }
    }

    private fun applyVideoCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT a.doctor.id, COUNT(a) FROM Appointment a
                   WHERE a.doctor.id IN :ids AND a.startAt >= :start AND a.startAt < :end
                   AND LOWER(a.location) LIKE '%video%'
                   GROUP BY a.doctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("start", window.startInstant)
                .setParameter("end", window.endExclusiveInstant)
        } else {
            em.createQuery(
                """SELECT a.doctor.id, COUNT(a) FROM Appointment a
                   WHERE a.doctor.id IN :ids AND LOWER(a.location) LIKE '%video%'
                   GROUP BY a.doctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        assignGroupedCounts(builders, q) { video = it }
    }

    private fun applyActivePatientCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT a.doctor.id, COUNT(DISTINCT a.patient.id) FROM Appointment a
                   WHERE a.doctor.id IN :ids AND a.startAt >= :start AND a.startAt < :end
                   GROUP BY a.doctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("start", window.startInstant)
                .setParameter("end", window.endExclusiveInstant)
        } else {
            em.createQuery(
                """SELECT a.doctor.id, COUNT(DISTINCT a.patient.id) FROM Appointment a
                   WHERE a.doctor.id IN :ids AND a.status <> 'CANCELLED'
                   GROUP BY a.doctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        assignGroupedCounts(builders, q) { activePatients = it }
    }

    private fun applyPatientsCreatedCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT p.createdByDoctor.id, COUNT(p) FROM PatientProfile p
                   WHERE p.createdByDoctor.id IN :ids AND p.createdAt >= :start AND p.createdAt < :end
                   GROUP BY p.createdByDoctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("start", window.odtStart)
                .setParameter("end", window.odtEndExclusive)
        } else {
            em.createQuery(
                """SELECT p.createdByDoctor.id, COUNT(p) FROM PatientProfile p
                   WHERE p.createdByDoctor.id IN :ids GROUP BY p.createdByDoctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        assignGroupedCounts(builders, q) { patientsCreated = it }
    }

    private fun applyDocumentsUploadedCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT d.uploadedByDoctor.id, COUNT(d) FROM PatientDocument d
                   WHERE d.uploadedByDoctor.id IN :ids AND d.date >= :from AND d.date <= :to
                   GROUP BY d.uploadedByDoctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("from", window.docFrom)
                .setParameter("to", window.docToInclusive)
        } else {
            em.createQuery(
                """SELECT d.uploadedByDoctor.id, COUNT(d) FROM PatientDocument d
                   WHERE d.uploadedByDoctor.id IN :ids GROUP BY d.uploadedByDoctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        assignGroupedCounts(builders, q) { documentsUploaded = it }
    }

    private fun applyTreatmentPlanCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT tp.attendingDoctor.id, COUNT(tp) FROM TreatmentPlan tp
                   WHERE tp.attendingDoctor.id IN :ids AND tp.createdAt >= :start AND tp.createdAt < :end
                   GROUP BY tp.attendingDoctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("start", window.odtStart)
                .setParameter("end", window.odtEndExclusive)
        } else {
            em.createQuery(
                """SELECT tp.attendingDoctor.id, COUNT(tp) FROM TreatmentPlan tp
                   WHERE tp.attendingDoctor.id IN :ids GROUP BY tp.attendingDoctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        assignGroupedCounts(builders, q) { treatmentPlans = it }
    }

    private fun applyRemoteTaskCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT t.doctor.id, COUNT(t) FROM RemoteCareTask t
                   WHERE t.doctor.id IN :ids AND t.createdAt >= :start AND t.createdAt < :end
                   GROUP BY t.doctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("start", window.startInstant)
                .setParameter("end", window.endExclusiveInstant)
        } else {
            em.createQuery(
                """SELECT t.doctor.id, COUNT(t) FROM RemoteCareTask t
                   WHERE t.doctor.id IN :ids GROUP BY t.doctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        assignGroupedCounts(builders, q) { remoteTasks = it }
    }

    private fun applyConsultationNoteCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT n.doctorId, COUNT(n) FROM ConsultationNote n
                   WHERE n.doctorId IN :ids AND n.createdAt >= :start AND n.createdAt < :end
                   GROUP BY n.doctorId""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("start", window.startInstant)
                .setParameter("end", window.endExclusiveInstant)
        } else {
            em.createQuery(
                """SELECT n.doctorId, COUNT(n) FROM ConsultationNote n
                   WHERE n.doctorId IN :ids GROUP BY n.doctorId""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        assignGroupedCounts(builders, q) { consultationNotes = it }
    }

    private fun applyPatientFormCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT pf.createdByDoctor.id, COUNT(pf) FROM PatientForm pf
                   WHERE pf.createdByDoctor.id IN :ids AND pf.createdAt >= :start AND pf.createdAt < :end
                   GROUP BY pf.createdByDoctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("start", window.odtStart)
                .setParameter("end", window.odtEndExclusive)
        } else {
            em.createQuery(
                """SELECT pf.createdByDoctor.id, COUNT(pf) FROM PatientForm pf
                   WHERE pf.createdByDoctor.id IN :ids GROUP BY pf.createdByDoctor.id""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        assignGroupedCounts(builders, q) { patientForms = it }
    }

    private fun applyAiDraftNoteCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        doctorIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ) {
        val q = if (window != null) {
            em.createQuery(
                """SELECT a.doctorId, COUNT(a) FROM AiDraftNote a
                   WHERE a.doctorId IN :ids AND a.createdAt >= :start AND a.createdAt < :end
                   GROUP BY a.doctorId""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
                .setParameter("start", window.startInstant)
                .setParameter("end", window.endExclusiveInstant)
        } else {
            em.createQuery(
                """SELECT a.doctorId, COUNT(a) FROM AiDraftNote a
                   WHERE a.doctorId IN :ids GROUP BY a.doctorId""",
                Array<Any>::class.java,
            ).setParameter("ids", doctorIds)
        }
        assignGroupedCounts(builders, q) { aiDraftNotes = it }
    }

    private fun applyLastActive(builders: Map<Long, DoctorMetricsBuilder>, doctorIds: Collection<Long>) {
        val maxByDoctor = mutableMapOf<Long, Instant?>()

        fun mergeMax(jpql: String, instantAt: (Array<Any>) -> Instant?) {
            val q = em.createQuery(jpql, Array<Any>::class.java).setParameter("ids", doctorIds)
            for (row in q.resultList) {
                val doctorId = (row[0] as Number).toLong()
                val instant = instantAt(row) ?: continue
                val prev = maxByDoctor[doctorId]
                maxByDoctor[doctorId] = if (prev == null || instant.isAfter(prev)) instant else prev
            }
        }

        mergeMax(
            "SELECT a.doctor.id, MAX(a.startAt) FROM Appointment a WHERE a.doctor.id IN :ids GROUP BY a.doctor.id",
        ) { row -> row[1] as? Instant }
        mergeMax(
            """SELECT tp.attendingDoctor.id, MAX(tp.updatedAt) FROM TreatmentPlan tp
               WHERE tp.attendingDoctor.id IN :ids GROUP BY tp.attendingDoctor.id""",
        ) { row -> (row[1] as? OffsetDateTime)?.toInstant() }
        mergeMax(
            "SELECT t.doctor.id, MAX(t.updatedAt) FROM RemoteCareTask t WHERE t.doctor.id IN :ids GROUP BY t.doctor.id",
        ) { row -> row[1] as? Instant }
        mergeMax(
            "SELECT n.doctorId, MAX(n.createdAt) FROM ConsultationNote n WHERE n.doctorId IN :ids GROUP BY n.doctorId",
        ) { row -> row[1] as? Instant }
        mergeMax(
            "SELECT a.doctorId, MAX(a.createdAt) FROM AiDraftNote a WHERE a.doctorId IN :ids GROUP BY a.doctorId",
        ) { row -> row[1] as? Instant }
        mergeMax(
            """SELECT pf.createdByDoctor.id, MAX(pf.createdAt) FROM PatientForm pf
               WHERE pf.createdByDoctor.id IN :ids GROUP BY pf.createdByDoctor.id""",
        ) { row -> (row[1] as? OffsetDateTime)?.toInstant() }

        for ((doctorId, instant) in maxByDoctor) {
            builders[doctorId]?.lastActiveAt = instant
        }
    }

    private fun loadAiRequestsByUserId(
        userIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ): Map<Long, Long> {
        if (userIds.isEmpty()) return emptyMap()
        val q = if (window != null) {
            em.createQuery(
                """SELECT c.userId, COALESCE(SUM(c.requestCount), 0L) FROM AiUsageCounter c
                   WHERE c.userId IN :ids AND c.role = :role
                   AND c.usageDate >= :from AND c.usageDate <= :to
                   GROUP BY c.userId""",
                Array<Any>::class.java,
            ).setParameter("ids", userIds)
                .setParameter("role", Role.DOCTOR)
                .setParameter("from", window.usageFrom)
                .setParameter("to", window.usageToInclusive)
        } else {
            em.createQuery(
                """SELECT c.userId, COALESCE(SUM(c.requestCount), 0L) FROM AiUsageCounter c
                   WHERE c.userId IN :ids AND c.role = :role GROUP BY c.userId""",
                Array<Any>::class.java,
            ).setParameter("ids", userIds)
                .setParameter("role", Role.DOCTOR)
        }
        return q.resultList.associate { row ->
            (row[0] as Number).toLong() to (row[1] as Number).toLong()
        }
    }

    private fun loadMaxAiUsageDateByUserId(
        userIds: Collection<Long>,
        window: AdminDoctorActivityService.BoundedWindow?,
    ): Map<Long, LocalDate> {
        if (userIds.isEmpty()) return emptyMap()
        val q = if (window != null) {
            em.createQuery(
                """SELECT c.userId, MAX(c.usageDate) FROM AiUsageCounter c
                   WHERE c.userId IN :ids AND c.role = :role
                   AND c.usageDate >= :from AND c.usageDate <= :to
                   GROUP BY c.userId""",
                Array<Any>::class.java,
            ).setParameter("ids", userIds)
                .setParameter("role", Role.DOCTOR)
                .setParameter("from", window.usageFrom)
                .setParameter("to", window.usageToInclusive)
        } else {
            em.createQuery(
                """SELECT c.userId, MAX(c.usageDate) FROM AiUsageCounter c
                   WHERE c.userId IN :ids AND c.role = :role GROUP BY c.userId""",
                Array<Any>::class.java,
            ).setParameter("ids", userIds)
                .setParameter("role", Role.DOCTOR)
        }
        return q.resultList.mapNotNull { row ->
            val userId = (row[0] as Number).toLong()
            val date = row[1] as? LocalDate ?: return@mapNotNull null
            userId to date
        }.toMap()
    }

    private fun assignGroupedCounts(
        builders: Map<Long, DoctorMetricsBuilder>,
        query: Query,
        assign: DoctorMetricsBuilder.(Long) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any>>
        for (row in rows) {
            val doctorId = (row[0] as Number).toLong()
            val count = (row[1] as Number).toLong()
            builders[doctorId]?.assign(count)
        }
    }

    private class DoctorMetricsBuilder {
        var appointmentsBooked: Long = 0
        var completed: Long = 0
        var cancelled: Long = 0
        var inProgress: Long = 0
        var video: Long = 0
        var activePatients: Long = 0
        var patientsCreated: Long = 0
        var documentsUploaded: Long = 0
        var treatmentPlans: Long = 0
        var remoteTasks: Long = 0
        var consultationNotes: Long = 0
        var patientForms: Long = 0
        var aiDraftNotes: Long = 0
        var lastActiveAt: Instant? = null

        fun toMetrics() = DoctorRowMetrics(
            appointmentsBooked = appointmentsBooked,
            completed = completed,
            cancelled = cancelled,
            inProgress = inProgress,
            video = video,
            activePatients = activePatients,
            patientsCreated = patientsCreated,
            documentsUploaded = documentsUploaded,
            treatmentPlans = treatmentPlans,
            remoteTasks = remoteTasks,
            consultationNotes = consultationNotes,
            patientForms = patientForms,
            aiDraftNotes = aiDraftNotes,
            lastActiveAt = lastActiveAt,
        )
    }
}
