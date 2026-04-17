package com.shifa.repo

import com.shifa.domain.SystemConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface SystemConfigRepository : JpaRepository<SystemConfig, Long> {
    fun findByKey(key: String): Optional<SystemConfig>
}
