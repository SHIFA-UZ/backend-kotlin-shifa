package com.shifa.appointments.api.dto

import com.shifa.appointments.domain.VideoJoinWindow

/** When the video room can be joined (ISO-8601 UTC instants); see [VideoJoinWindow]. */
data class JoinWindowDto(val from: String, val to: String) {
    companion object {
        fun of(window: VideoJoinWindow?): JoinWindowDto? = window?.let { JoinWindowDto(it.from.toString(), it.to.toString()) }
    }
}
