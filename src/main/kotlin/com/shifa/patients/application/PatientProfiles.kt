package com.shifa.patients.application

import com.shifa.patients.domain.PatientProfile
import com.shifa.patients.infrastructure.PatientProfileRepository
import java.time.OffsetDateTime
import java.util.Optional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service

/**
 * The feature's door to PatientProfile records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [PatientProfileRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class PatientProfiles(private val repo: PatientProfileRepository) {
    fun clearCreatedByDoctor(doctorId: Long): Int = repo.clearCreatedByDoctor(doctorId)
    fun findByPhone(phone: String): Optional<PatientProfile> = repo.findByPhone(phone)
    fun findByEmail(email: String): Optional<PatientProfile> = repo.findByEmail(email)
    fun findByPhoneNormalized(phoneNormalized: String): Optional<PatientProfile> = repo.findByPhoneNormalized(phoneNormalized)
    fun findByUserId(userId: Long): Optional<PatientProfile> = repo.findByUserId(userId)
    fun searchByQuery(query: String, pageable: org.springframework.data.domain.Pageable): List<PatientProfile> = repo.searchByQuery(query, pageable)
    fun findUserIdsBySearch(q: String): List<Long> = repo.findUserIdsBySearch(q)
    fun findByIdWithUser(id: Long): Optional<PatientProfile> = repo.findByIdWithUser(id)
    fun findClinicRosterForDoctors(clinicDoctorIds: Collection<Long>, q: String?, pageable: Pageable): Page<PatientProfile> = repo.findClinicRosterForDoctors(clinicDoctorIds, q, pageable)
    fun findClinicRosterScopedToDoctor(actorDoctorId: Long, q: String?, pageable: Pageable): Page<PatientProfile> = repo.findClinicRosterScopedToDoctor(actorDoctorId, q, pageable)
    fun findVisiblePatientIdsLinkedToDoctors(doctorIds: Collection<Long>): List<Long> = repo.findVisiblePatientIdsLinkedToDoctors(doctorIds)
    fun countByCreatedByDoctor_Id(createdByDoctorId: Long): Long = repo.countByCreatedByDoctor_Id(createdByDoctorId)
    fun countCreatedByDoctorInDateRange(doctorId: Long, start: OffsetDateTime, endExclusive: OffsetDateTime): Long = repo.countCreatedByDoctorInDateRange(doctorId, start, endExclusive)
    fun countByUserIsNull(): Long = repo.countByUserIsNull()
    fun countByUserIsNotNull(): Long = repo.countByUserIsNotNull()
    fun findByFcmToken(fcmToken: String): List<PatientProfile> = repo.findByFcmToken(fcmToken)
    fun countWithDeviceRegistered(): Long = repo.countWithDeviceRegistered()
    fun countAppUsersWithoutDevice(): Long = repo.countAppUsersWithoutDevice()
    fun findUserIdsWithDeviceRegistered(): List<Long> = repo.findUserIdsWithDeviceRegistered()
    fun findWithoutAppAccount(search: String?, pageable: Pageable): Page<PatientProfile> = repo.findWithoutAppAccount(search, pageable)
    fun count(): Long = repo.count()
    fun delete(entity: PatientProfile) = repo.delete(entity)
    fun findAll(): List<PatientProfile> = repo.findAll()
    fun findAll(pageable: Pageable): Page<PatientProfile> = repo.findAll(pageable)
    fun findAll(sort: Sort): List<PatientProfile> = repo.findAll(sort)
    fun findById(id: Long): Optional<PatientProfile> = repo.findById(id)
    fun save(entity: PatientProfile): PatientProfile = repo.save(entity)
    fun saveAll(entities: Iterable<PatientProfile>): List<PatientProfile> = repo.saveAll(entities)
}
