package com.shifa.doctors.api.dto

data class ColleagueDto(val doctorId: Long, val displayName: String)

data class PracticeSessionDto(
    val principalRole: String,
    val clinicIds: List<Long>,
    val colleagues: List<ColleagueDto>
)
