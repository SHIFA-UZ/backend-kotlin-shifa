package com.shifa.doctors.api.dto

data class GroupDto(
    val id: Long?,
    val name: String,
    val sortOrder: Int
)

data class UpsertGroupRequest(
    val name: String,
    val sortOrder: Int = 0
)
