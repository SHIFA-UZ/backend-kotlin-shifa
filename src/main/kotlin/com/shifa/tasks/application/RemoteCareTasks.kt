package com.shifa.tasks.application

import com.shifa.tasks.domain.RemoteCareTask
import com.shifa.tasks.infrastructure.RemoteCareTaskRepository
import java.time.Instant
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to RemoteCareTask records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [RemoteCareTaskRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class RemoteCareTasks(private val repo: RemoteCareTaskRepository) {
    fun findByDoctorIdOrderByCreatedAtDesc(doctorId: Long): List<RemoteCareTask> = repo.findByDoctorIdOrderByCreatedAtDesc(doctorId)
    fun findByPatientIdOrderByCreatedAtDesc(patientId: Long): List<RemoteCareTask> = repo.findByPatientIdOrderByCreatedAtDesc(patientId)
    fun findByPatientIdAndStatus(patientId: Long, status: RemoteCareTask.Status): List<RemoteCareTask> = repo.findByPatientIdAndStatus(patientId, status)
    fun findByDoctorIdAndStatus(doctorId: Long, status: RemoteCareTask.Status): List<RemoteCareTask> = repo.findByDoctorIdAndStatus(doctorId, status)
    fun countByDoctorInDateRange(doctorId: Long, start: Instant, endExclusive: Instant): Long = repo.countByDoctorInDateRange(doctorId, start, endExclusive)
    fun countByDoctorAllTime(doctorId: Long): Long = repo.countByDoctorAllTime(doctorId)
    fun findMaxUpdatedAtByDoctorId(doctorId: Long): Instant? = repo.findMaxUpdatedAtByDoctorId(doctorId)
    fun listByDoctorCreatedBetween(doctorId: Long, start: Instant, endExclusive: Instant): List<RemoteCareTask> = repo.listByDoctorCreatedBetween(doctorId, start, endExclusive)
    fun findActiveTaskPatientIdsIn(patientIds: Collection<Long>, status: RemoteCareTask.Status = RemoteCareTask.Status.ACTIVE): List<Long> = repo.findActiveTaskPatientIdsIn(patientIds, status)
    fun deleteAll(entities: Iterable<RemoteCareTask>) = repo.deleteAll(entities)
    fun deleteAll() = repo.deleteAll()
    fun findById(id: Long): Optional<RemoteCareTask> = repo.findById(id)
    fun save(entity: RemoteCareTask): RemoteCareTask = repo.save(entity)
}
