package com.shifa.web

import com.shifa.domain.DoctorServiceGroup
import com.shifa.repo.DoctorServiceGroupRepository
import com.shifa.security.DoctorPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/doctors/me/service-groups")
class DoctorServiceGroupController(
    private val groups: DoctorServiceGroupRepository
) {
    data class GroupDto(
        val id: Long?,
        val name: String,
        val sortOrder: Int
    )

    data class UpsertGroupRequest(
        val name: String,
        val sortOrder: Int = 0
    )

    @GetMapping
    @Transactional(readOnly = true)
    fun list(@AuthenticationPrincipal principal: DoctorPrincipal): List<GroupDto> {
        val doctorId = principal.profile.id ?: return emptyList()
        return groups.findByDoctorIdOrderBySortOrderAscIdAsc(doctorId).map { it.toDto() }
    }

    @PostMapping
    @Transactional
    fun create(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @RequestBody body: UpsertGroupRequest
    ): GroupDto {
        val doctor = principal.profile
        val name = body.name.trim()
        if (name.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Group name is required")
        }
        val saved = groups.save(
            DoctorServiceGroup(
                doctor = doctor,
                name = name,
                sortOrder = body.sortOrder
            )
        )
        return saved.toDto()
    }

    @PatchMapping("/{groupId}")
    @Transactional
    fun update(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable groupId: Long,
        @RequestBody body: UpsertGroupRequest
    ): GroupDto {
        val doctorId = principal.profile.id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val group = groups.findById(groupId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")
        }
        if (group.doctor.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Group does not belong to current doctor")
        }
        val name = body.name.trim()
        if (name.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Group name is required")
        }
        group.name = name
        group.sortOrder = body.sortOrder
        group.updatedAt = Instant.now()
        return groups.save(group).toDto()
    }

    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun delete(
        @AuthenticationPrincipal principal: DoctorPrincipal,
        @PathVariable groupId: Long
    ) {
        val doctorId = principal.profile.id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val group = groups.findById(groupId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")
        }
        if (group.doctor.id != doctorId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Group does not belong to current doctor")
        }
        groups.delete(group)
    }

    private fun DoctorServiceGroup.toDto() = GroupDto(id = id, name = name, sortOrder = sortOrder)
}
