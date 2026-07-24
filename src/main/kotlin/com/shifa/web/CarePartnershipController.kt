package com.shifa.web

import com.shifa.security.DoctorPrincipal
import com.shifa.service.CarePartnershipProgressDto
import com.shifa.service.CarePartnershipDto
import com.shifa.service.CarePartnerSearchHit
import com.shifa.service.CarePartnershipService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/care-partnerships")
class CarePartnershipController(
    private val service: CarePartnershipService,
) {

    data class InviteRequest(
        val patientId: Long,
        val partnerDoctorId: Long,
        val specialtyRequested: String? = null,
        val message: String? = null,
        val originatingAppointmentId: Long? = null,
    )

    data class ProgressRequest(val body: String)

    @GetMapping("/partners/search")
    fun searchPartners(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestParam(required = false) country: String?,
        @RequestParam(required = false) profession: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "30") limit: Int,
    ): List<CarePartnerSearchHit> =
        service.searchPartners(
            actorDoctorId = principal.profile.id!!,
            country = country,
            profession = profession,
            q = q,
            limit = limit,
        )

    @GetMapping
    fun listMine(@AuthenticationPrincipal principal: DoctorPrincipal): List<CarePartnershipDto> =
        service.listMine(principal.profile.id!!)

    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long,
    ): CarePartnershipDto = service.get(id, principal.profile.id!!)

    @PostMapping
    fun invite(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody req: InviteRequest,
    ): CarePartnershipDto =
        service.invite(
            initiatingDoctorId = principal.profile.id!!,
            patientId = req.patientId,
            partnerDoctorId = req.partnerDoctorId,
            specialtyRequested = req.specialtyRequested,
            message = req.message,
            originatingAppointmentId = req.originatingAppointmentId,
        )

    @PostMapping("/{id}/accept")
    fun accept(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long,
    ): CarePartnershipDto = service.accept(id, principal.profile.id!!)

    @PostMapping("/{id}/decline")
    fun decline(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long,
    ): CarePartnershipDto = service.decline(id, principal.profile.id!!)

    @PostMapping("/{id}/complete")
    fun complete(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long,
    ): CarePartnershipDto = service.complete(id, principal.profile.id!!)

    @PostMapping("/{id}/cancel")
    fun cancel(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long,
    ): CarePartnershipDto = service.cancel(id, principal.profile.id!!)

    @GetMapping("/{id}/progress")
    fun listProgress(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long,
    ): List<CarePartnershipProgressDto> = service.listProgress(id, principal.profile.id!!)

    @PostMapping("/{id}/progress")
    fun addProgress(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable id: Long,
        @RequestBody req: ProgressRequest,
    ): CarePartnershipProgressDto = service.addProgress(id, principal.profile.id!!, req.body)
}
