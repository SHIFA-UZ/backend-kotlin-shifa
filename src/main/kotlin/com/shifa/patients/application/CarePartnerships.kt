package com.shifa.patients.application

import com.shifa.patients.domain.CarePartnership
import com.shifa.patients.infrastructure.CarePartnershipRepository
import org.springframework.stereotype.Service

/**
 * The feature's door to CarePartnership records for its controllers and for other features (roadmap Phase 5 step 4):
 * nothing outside this layer touches [CarePartnershipRepository]. Method names match the repository so the callers only changed a
 * type; use-case methods that replace raw record access grow here.
 */
@Service
@Suppress("FunctionNaming", "FunctionParameterNaming", "MaxLineLength", "TooManyFunctions")
class CarePartnerships(private val repo: CarePartnershipRepository) {
    fun existsActiveForDoctorAndPatient(doctorId: Long, patientId: Long): Boolean = repo.existsActiveForDoctorAndPatient(doctorId, patientId)
}
