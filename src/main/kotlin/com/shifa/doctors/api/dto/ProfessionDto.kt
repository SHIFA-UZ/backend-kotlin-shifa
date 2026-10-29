package com.shifa.doctors.api.dto

data class ProfessionDto(
    val id: Long,
    val english: String,
    val uzbek: String,
    val category: String?,
    val displayOrder: Int
)
