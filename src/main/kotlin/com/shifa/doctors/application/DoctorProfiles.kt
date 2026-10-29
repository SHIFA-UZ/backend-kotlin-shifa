package com.shifa.doctors.application

import com.shifa.doctors.domain.DoctorProfile
import com.shifa.doctors.infrastructure.DoctorProfileRepository
import java.util.Optional
import org.springframework.stereotype.Service

/**
 * The feature's door to DoctorProfile records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [DoctorProfileRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class DoctorProfiles(private val repo: DoctorProfileRepository) {
    fun findAllByPracticeClinic_Id(clinicId: Long): List<DoctorProfile> = repo.findAllByPracticeClinic_Id(clinicId)
    fun findByUserId(userId: Long): Optional<DoctorProfile> = repo.findByUserId(userId)
    fun findByUserIdWithPracticeClinic(userId: Long): Optional<DoctorProfile> = repo.findByUserIdWithPracticeClinic(userId)
    fun findByFcmToken(fcmToken: String): List<DoctorProfile> = repo.findByFcmToken(fcmToken)
    fun countWithDeviceRegistered(): Long = repo.countWithDeviceRegistered()
    fun findUserIdsWithDeviceRegistered(): List<Long> = repo.findUserIdsWithDeviceRegistered()
    fun searchByQuery(query: String): List<DoctorProfile> = repo.searchByQuery(query)
    fun findAllByUserEnabled(): List<DoctorProfile> = repo.findAllByUserEnabled()
    fun findPartnerSearchCandidates(excludeDoctorId: Long, search: String?, profession: String?, pageable: org.springframework.data.domain.Pageable): List<DoctorProfile> = repo.findPartnerSearchCandidates(excludeDoctorId, search, profession, pageable)
    fun searchWithFilters(search: String?, profession: String?): List<DoctorProfile> = repo.searchWithFilters(search, profession)
    fun findUserIdsBySearch(q: String): List<Long> = repo.findUserIdsBySearch(q)
    fun findAllForAdminActivitySearch(search: String?): List<DoctorProfile> = repo.findAllForAdminActivitySearch(search)
    fun findByUserIdInWithPracticeClinic(userIds: Collection<Long>): List<DoctorProfile> = repo.findByUserIdInWithPracticeClinic(userIds)
    fun findByIdForUpdate(id: Long): Optional<DoctorProfile> = repo.findByIdForUpdate(id)
    fun delete(entity: DoctorProfile) = repo.delete(entity)
    fun findById(id: Long): Optional<DoctorProfile> = repo.findById(id)
    fun save(entity: DoctorProfile): DoctorProfile = repo.save(entity)
    fun saveAll(entities: Iterable<DoctorProfile>): List<DoctorProfile> = repo.saveAll(entities)
}
