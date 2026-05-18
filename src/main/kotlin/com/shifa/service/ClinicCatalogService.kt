package com.shifa.service

import com.shifa.domain.DoctorService
import com.shifa.domain.DoctorServicePrice
import com.shifa.domain.TreatmentPlanCatalogItem
import com.shifa.domain.TreatmentPlanCatalogItemDoctor
import com.shifa.repo.DoctorProfileRepository
import com.shifa.repo.DoctorServicePriceRepository
import com.shifa.repo.DoctorServiceRepository
import com.shifa.repo.TreatmentPlanCatalogItemDoctorRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@Service
class ClinicCatalogService(
    private val doctors: DoctorProfileRepository,
    private val catalogDoctorLinks: TreatmentPlanCatalogItemDoctorRepository,
    private val doctorServices: DoctorServiceRepository,
    private val prices: DoctorServicePriceRepository,
) {
    fun resolvePracticeDoctorIds(clinicId: Long): Set<Long> =
        doctors.findAllByPracticeClinic_Id(clinicId).mapNotNull { it.id }.toSet()

    fun validateAssignment(
        clinicId: Long,
        appliesToAllDoctors: Boolean,
        assignedDoctorProfileIds: List<Long>,
    ): Set<Long> {
        val practiceIds = resolvePracticeDoctorIds(clinicId)
        if (appliesToAllDoctors) {
            return practiceIds
        }
        val wanted = assignedDoctorProfileIds.toSet()
        val invalid = wanted - practiceIds
        if (invalid.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Doctor(s) not in clinic practice: ${invalid.joinToString()}",
            )
        }
        if (wanted.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Select at least one doctor or enable all doctors",
            )
        }
        return wanted
    }

    @Transactional
    fun replaceExplicitAssignments(catalogItem: TreatmentPlanCatalogItem, doctorIds: Collection<Long>) {
        catalogDoctorLinks.deleteByCatalogItem_Id(catalogItem.id)
        for (doctorId in doctorIds) {
            val doctor = doctors.findById(doctorId).orElseThrow {
                ResponseStatusException(HttpStatus.BAD_REQUEST, "Doctor not found: $doctorId")
            }
            catalogDoctorLinks.save(
                TreatmentPlanCatalogItemDoctor(
                    catalogItem = catalogItem,
                    doctor = doctor,
                ),
            )
        }
    }

    fun explicitAssignmentIds(catalogItemId: Long): List<Long> =
        catalogDoctorLinks.findByCatalogItem_Id(catalogItemId).mapNotNull { it.doctor.id }

    @Transactional
    fun syncCatalogItemToDoctorServices(catalog: TreatmentPlanCatalogItem) {
        val clinicId = catalog.clinic.id ?: return
        val targetIds: Set<Long> = if (catalog.appliesToAllDoctors) {
            resolvePracticeDoctorIds(clinicId)
        } else {
            explicitAssignmentIds(catalog.id).toSet()
        }

        val catalogActive = catalog.active

        for (doctorId in targetIds) {
            val doctor = doctors.findById(doctorId).orElseThrow {
                ResponseStatusException(HttpStatus.BAD_REQUEST, "Doctor not found: $doctorId")
            }
            val existing =
                doctorServices.findByDoctor_IdAndSourceCatalogItem_Id(doctorId, catalog.id)
            val isFree = catalog.defaultPriceMinor <= 0L
            val service =
                if (existing != null) {
                    existing.title = catalog.title
                    existing.description = null
                    existing.isActive = catalogActive
                    existing.isFreeConsultation = isFree
                    existing.group = null
                    existing.updatedAt = Instant.now()
                    doctorServices.save(existing)
                } else {
                    doctorServices.save(
                        DoctorService(
                            doctor = doctor,
                            title = catalog.title,
                            description = null,
                            isActive = catalogActive,
                            isFreeConsultation = isFree,
                            group = null,
                            sourceCatalogItem = catalog,
                        ),
                    )
                }
            prices.deleteByService_Id(service.id)
            if (catalogActive && !isFree) {
                prices.save(
                    DoctorServicePrice(
                        service = service,
                        location = null,
                        amountMinor = catalog.defaultPriceMinor,
                        currency = catalog.currency.trim().uppercase(),
                    ),
                )
            }
        }

        for (svc in doctorServices.findAllBySourceCatalogItem_Id(catalog.id)) {
            val docId = svc.doctor.id ?: continue
            if (docId !in targetIds) {
                svc.isActive = false
                svc.updatedAt = Instant.now()
                doctorServices.save(svc)
            }
        }
    }
}
